/*
 * ptrace_injector.c — minimal aarch64/arm64 library injector for Android 13+.
 *
 * Implements the classic crash-stop remote-call technique:
 *   1. PTRACE_ATTACH (via PTRACE_SEIZE which survives on modern kernels)
 *   2. PTRACE_GETREGSET to save the target's registers
 *   3. Remote syscall: mmap(NULL, 0x1000, RWX, MAP_PRIVATE|MAP_ANONYMOUS)
 *   4. POKEDATA the absolute path of the .so + dlopen address into the page
 *   5. Set up a fake stack frame, set PC = dlopen, x0 = path, x1 = RTLD_NOW
 *      and single-step until the call returns (detect via x30/link-register
 *      sentinel: dlopen's return clobbers x30 with our sentinel value).
 *   6. PTRACE_DETACH (restores original registers first if the call failed)
 *   7. Exit 0. The parent (MemInjectTool) verifies /proc/pid/maps afterwards.
 *
 * Build (on-device, bundled clang):
 *   clang --sysroot=$SYSROOT -O2 -static -o ptrace-injector ptrace_injector.c
 * (-static avoids needing the linker to resolve anything in the target env —
 *  the injector is a plain /system/bin-style static ELF run via su.)
 *
 * Only for instrumenting processes you own or have explicit permission to test.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/system_properties.h>

#ifndef PTRACE_SEIZE
#define PTRACE_SEIZE 0x4206
#endif
#ifndef PTRACE_INTERRUPT
#define PTRACE_INTERRUPT 0x4207
#endif
#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif
#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif

#define NT_PRSTATUS 1

/* Sentinel we plant in LR so we can detect "dlopen returned". */
#define CALL_SENTINEL 0x5E1F00D5ULL

/* RTLD_NOW for immediate symbol binding. */
#define RTLD_NOW_VAL 2

struct user_regs_struct_a64 {
    unsigned long long regs[31]; /* x0..x30 */
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static int g_pid = 0;

static void detach_and_exit(int code) {
    if (g_pid > 0) ptrace(PTRACE_DETACH, g_pid, 0, 0);
    exit(code);
}

static long ptrace_or_die(int req, void *addr, void *data, const char *what) {
    errno = 0;
    long r = ptrace(req, g_pid, addr, data);
    if (r == -1 && errno != 0) {
        fprintf(stderr, "ptrace %s failed: %s\n", what, strerror(errno));
        detach_and_exit(4);
    }
    return r;
}

/* Read arbitrary target memory (handles word-unaligned via process_vm_readv,
 * falling back to /proc/pid/mem). Returns bytes actually read. */
static size_t read_mem(unsigned long long addr, void *buf, size_t len) {
    struct iovec local = { buf, len };
    struct iovec remote = { (void *)addr, len };
    ssize_t n = process_vm_readv(g_pid, &local, 1, &remote, 1, 0);
    if (n > 0) return (size_t)n;
    /* Fallback: /proc/pid/mem pread */
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", g_pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    n = pread(fd, buf, len, (off_t)addr);
    close(fd);
    return n > 0 ? (size_t)n : 0;
}

static size_t write_mem(unsigned long long addr, const void *buf, size_t len) {
    struct iovec local = { (void *)buf, len };
    struct iovec remote = { (void *)addr, len };
    ssize_t n = process_vm_writev(g_pid, &local, 1, &remote, 1, 0);
    if (n > 0) return (size_t)n;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", g_pid);
    int fd = open(path, O_WRONLY);
    if (fd < 0) return 0;
    n = pwrite(fd, buf, len, (off_t)addr);
    close(fd);
    return n > 0 ? (size_t)n : 0;
}

/* Resolve dlopen in the TARGET process by walking /proc/pid/maps and reading
 * the ELF dynsym of the linker + libc... — too heavy for this tool. Instead we
 * ask libdl's base from the target's maps and locate dlopen via the Android
 * linker's exported symbol table using a simplified reader for the .so.
 * Android 7+ moved dlopen into the linker; the linker exports
 * "__loader_dlopen" and "dlopen" from /system/bin/linker64. We find the
 * linker64 mapping then parse its .dynsym for "dlopen". */
struct elf_info { unsigned long long base, symaddr; };

static int find_symbol_in_file(const char *path, unsigned long long load_bias,
                               const char *symbol, unsigned long long *out) {
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    unsigned char e_ident[16];
    if (fread(e_ident, 1, 16, f) != 16) { fclose(f); return 0; }
    if (e_ident[0] != 0x7f || e_ident[1] != 'E') { fclose(f); return 0; }
    int is64 = e_ident[4] == 2;
    if (!is64) { fclose(f); return 0; } /* arm64 device, need ELF64 */
    fseek(f, 0x20, SEEK_SET); unsigned long long e_shoff; if (fread(&e_shoff, 8, 1, f) != 1) { fclose(f); return 0; }
    fseek(f, 0x3a, SEEK_SET); unsigned short e_shentsize, e_shnum, e_shstrndx;
    if (fread(&e_shentsize, 2, 1, f) != 1) { fclose(f); return 0; }
    if (fread(&e_shnum, 2, 1, f) != 1) { fclose(f); return 0; }
    unsigned long long symtab_off = 0, symtab_size = 0, symtab_entsize = 24, strtab_off = 0, strtab_size = 0;
    for (int i = 0; i < e_shnum; i++) {
        unsigned char sh[64];
        fseek(f, e_shoff + (unsigned long)i * e_shentsize, SEEK_SET);
        if (fread(sh, 1, 64, f) != 64) break;
        unsigned int sh_type = *(unsigned int *)(sh + 4);
        unsigned long long sh_offset = *(unsigned long long *)(sh + 24);
        unsigned long long sh_size = *(unsigned long long *)(sh + 32);
        unsigned long long sh_entsize = *(unsigned long long *)(sh + 56);
        if (sh_type == 2 /*SHT_SYMTAB*/ || sh_type == 11 /*SHT_DYNSYM*/) {
            symtab_off = sh_offset; symtab_size = sh_size;
            symtab_entsize = sh_entsize ? sh_entsize : 24;
            /* linked strtab: sh_link at offset 40 */
            unsigned int sh_link = *(unsigned int *)(sh + 40);
            unsigned char strsh[64];
            fseek(f, e_shoff + (unsigned long)sh_link * e_shentsize, SEEK_SET);
            if (fread(strsh, 1, 64, f) == 64) {
                strtab_off = *(unsigned long long *)(strsh + 24);
                strtab_size = *(unsigned long long *)(strsh + 32);
            }
        }
    }
    int found = 0;
    if (symtab_off && strtab_off) {
        unsigned long nsyms = symtab_size / symtab_entsize;
        char *strs = malloc(strtab_size + 1);
        if (strs) {
            fseek(f, strtab_off, SEEK_SET);
            size_t got = fread(strs, 1, strtab_size, f);
            strs[got] = 0;
            for (unsigned long i = 0; i < nsyms && !found; i++) {
                unsigned char sym[24];
                fseek(f, symtab_off + i * symtab_entsize, SEEK_SET);
                if (fread(sym, 1, 24, f) != 24) break;
                unsigned int st_name = *(unsigned int *)sym;
                unsigned long long st_value = *(unsigned long long *)(sym + 8);
                if (st_name < strtab_size && strcmp(strs + st_name, symbol) == 0) {
                    *out = load_bias + st_value;
                    found = 1;
                }
            }
            free(strs);
        }
    }
    fclose(f);
    return found;
}

static int find_dlopen_in_target(unsigned long long *out) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", g_pid);
    FILE *f = fopen(maps_path, "r");
    if (!f) return 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        unsigned long long lo, hi;
        char perms[8], path[400];
        path[0] = 0;
        if (sscanf(line, "%llx-%llx %4s %*x %*x:%*x %*u %399s", &lo, &hi, perms, path) < 3) continue;
        if (perms[2] != 'x') continue; /* need executable mapping */
        if (strstr(path, "/linker64") == NULL && strstr(path, "/linker") == NULL) continue;
        /* load_bias: first executable segment's file offset is typically 0;
         * for the linker the base of the r-x mapping minus the ELF's vaddr of
         * that segment gives the bias. We approximate: bias = lo of the FIRST
         * mapping of that path (r-- or r-x). */
        unsigned long long bias = lo;
        fclose(f);
        unsigned long long addr = 0;
        if (find_symbol_in_file(path, bias, "dlopen", &addr)) { *out = addr; return 1; }
        if (find_symbol_in_file(path, bias, "__loader_dlopen", &addr)) { *out = addr; return 1; }
        /* Reopen maps and continue scanning further linker mappings. */
        f = fopen(maps_path, "r");
        if (!f) return 0;
        /* skip lines until we pass this one */
        while (fgets(line, sizeof(line), f)) { /* skip */ }
        break;
    }
    fclose(f);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <pid> <library_path> [function_name]\n", argv[0]);
        return 2;
    }
    g_pid = atoi(argv[1]);
    const char *lib = argv[2];
    const char *entry = argc > 3 ? argv[3] : NULL; /* accepted for CLI compat; dlopen runs init constructors */

    if (getuid() != 0) {
        fprintf(stderr, "ERROR: must run as root (su)\n");
        return 2;
    }

    /* 1. SEIZE (graceful attach that leaves the process running). */
    errno = 0;
    if (ptrace(PTRACE_SEIZE, g_pid, 0, PTRACE_O_TRACESYSGOOD) == -1) {
        fprintf(stderr, "PTRACE_SEIZE pid %d failed: %s\n", g_pid, strerror(errno));
        fprintf(stderr, "hints: run via 'su -c'; check SELinux (setenforce 0) and Yama ptrace_scope\n");
        return 3;
    }
    errno = 0;
    if (ptrace(PTRACE_INTERRUPT, g_pid, 0, 0) == -1) {
        fprintf(stderr, "PTRACE_INTERRUPT failed: %s\n", strerror(errno));
        detach_and_exit(3);
    }
    int status;
    if (waitpid(g_pid, &status, __WALL) < 0) {
        fprintf(stderr, "waitpid failed: %s\n", strerror(errno));
        detach_and_exit(3);
    }

    /* 2. Save registers. */
    struct user_regs_struct_a64 regs, saved;
    struct iovec regs_io = { &regs, sizeof(regs) };
    errno = 0;
    if (ptrace(PTRACE_GETREGSET, g_pid, (void *)NT_PRSTATUS, &regs_io) == -1) {
        fprintf(stderr, "GETREGSET failed: %s\n", strerror(errno));
        detach_and_exit(4);
    }
    memcpy(&saved, &regs, sizeof(regs));

    /* 3. Remote mmap an RWX page. */
    unsigned long long mmap_ret = 0;
    {
        struct user_regs_struct_a64 r = saved;
        memset(r.regs, 0, sizeof(r.regs));
        r.regs[8] = 222;                 /* __NR_mmap (aarch64) */
        r.regs[0] = 0;                   /* addr: NULL */
        r.regs[1] = 0x4000;              /* len: 16 KB */
        r.regs[2] = PROT_READ | PROT_WRITE | PROT_EXEC;
        r.regs[3] = MAP_PRIVATE | MAP_ANONYMOUS;
        r.regs[4] = -1;                  /* fd */
        r.regs[5] = 0;                   /* offset */
        struct iovec rio = { &r, sizeof(r) };
        ptrace(PTRACE_SETREGSET, g_pid, (void *)NT_PRSTATUS, &rio);
        ptrace(PTRACE_SYSCALL, g_pid, 0, 0);
        waitpid(g_pid, &status, __WALL);
        /* syscall-entry stop: fetch regs, check x8, continue to exit-stop */
        struct iovec cio = { &r, sizeof(r) };
        ptrace(PTRACE_GETREGSET, g_pid, (void *)NT_PRSTATUS, &cio);
        if (r.regs[8] != 222) {
            fprintf(stderr, "unexpected stop during remote mmap (x8=%llx)\n", r.regs[8]);
            detach_and_exit(5);
        }
        ptrace(PTRACE_SYSCALL, g_pid, 0, 0);
        waitpid(g_pid, &status, __WALL);
        ptrace(PTRACE_GETREGSET, g_pid, (void *)NT_PRSTATUS, &cio);
        mmap_ret = r.regs[0];
        if ((long long)mmap_ret < 0 && (long long)mmap_ret > -4096) {
            fprintf(stderr, "remote mmap failed: %lld\n", (long long)mmap_ret);
            detach_and_exit(5);
        }
        fprintf(stderr, "[i] remote page: 0x%llx\n", mmap_ret);
    }

    /* 4. Write path string into the page (offset 0), sentinel trampoline at
     *    offset 0x100 (BRK #0 → we actually don't need a trampoline: we set
     *    PC directly to dlopen and LR to page+0x8 where a BRK instruction
     *    (0xD4200020) is pre-planted so the return traps as SIGTRAP). */
    size_t liblen = strlen(lib) + 1;
    if (liblen > 0x1000) { fprintf(stderr, "library path too long\n"); detach_and_exit(2); }
    unsigned int brk_insn = 0xD4200020u;
    write_mem(mmap_ret + 0x8, &brk_insn, 4);
    write_mem(mmap_ret + 0x100, lib, liblen);

    /* 5. Locate dlopen in the target. */
    unsigned long long dlopen_addr = 0;
    if (!find_dlopen_in_target(&dlopen_addr)) {
        fprintf(stderr, "could not resolve dlopen in target (linker64 symbol scan failed)\n");
        detach_and_exit(6);
    }
    fprintf(stderr, "[i] dlopen @ 0x%llx\n", dlopen_addr);

    /* 6. Run dlopen(path, RTLD_NOW) in the target. */
    {
        struct user_regs_struct_a64 r = saved;
        r.regs[0] = mmap_ret + 0x100;    /* x0 = path */
        r.regs[1] = RTLD_NOW_VAL;        /* x1 = RTLD_NOW */
        r.regs[30] = mmap_ret + 0x8;     /* LR → BRK trap */
        r.regs[31] = saved.sp - 0x400;   /* SP: safe scratch below original */
        r.pc = dlopen_addr;
        struct iovec rio = { &r, sizeof(r) };
        ptrace(PTRACE_SETREGSET, g_pid, (void *)NT_PRSTATUS, &rio);
    }
    int steps = 0;
    unsigned long long handle = 0;
    while (steps < 200000) {
        ptrace(PTRACE_CONT, g_pid, 0, 0);
        if (waitpid(g_pid, &status, __WALL) < 0) break;
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            fprintf(stderr, "target died during dlopen (status=0x%x)\n", status);
            return 7;
        }
        int sig = WSTOPSIG(status);
        if (sig == SIGTRAP) {
            struct iovec rio = { &regs, sizeof(regs) };
            ptrace(PTRACE_GETREGSET, g_pid, (void *)NT_PRSTATUS, &rio);
            if (regs.pc == mmap_ret + 0x8 || regs.regs[30] == mmap_ret + 0x8) {
                handle = regs.regs[0];
                break;
            }
        } else if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL || sig == SIGABRT) {
            /* Real fault inside dlopen: restore regs, detach, let it crash? No:
             * restore original state so the app survives and report failure. */
            fprintf(stderr, "target raised signal %d during dlopen; restoring\n", sig);
            struct user_regs_struct_a64 r2 = saved;
            struct iovec rio = { &r2, sizeof(r2) };
            ptrace(PTRACE_SETREGSET, g_pid, (void *)NT_PRSTATUS, &rio);
            ptrace(PTRACE_CONT, g_pid, 0, sig);
            fprintf(stderr, "dlopen faulted — library likely incompatible (arch/NDK)\n");
            return 8;
        }
        steps++;
    }
    if (handle == 0 || ((long long)handle == -1)) {
        fprintf(stderr, "dlopen returned failure (handle=0x%llx)\n", handle);
        /* restore original regs & detach cleanly */
        struct user_regs_struct_a64 r2 = saved;
        struct iovec rio = { &r2, sizeof(r2) };
        ptrace(PTRACE_SETREGSET, g_pid, (void *)NT_PRSTATUS, &rio);
        detach_and_exit(9);
    }
    fprintf(stderr, "[+] dlopen OK, handle=0x%llx\n", handle);

    /* 7. Restore original registers, munmap the scratch page, detach. */
    {
        struct user_regs_struct_a64 r = saved;
        r.regs[8] = 215;                 /* __NR_munmap */
        r.regs[0] = mmap_ret;
        r.regs[1] = 0x4000;
        struct iovec rio = { &r, sizeof(r) };
        ptrace(PTRACE_SETREGSET, g_pid, (void *)NT_PRSTATUS, &rio);
        ptrace(PTRACE_SYSCALL, g_pid, 0, 0);
        waitpid(g_pid, &status, __WALL);
        ptrace(PTRACE_SYSCALL, g_pid, 0, 0);
        waitpid(g_pid, &status, __WALL);
        /* restore pristine regs */
        struct iovec sio = { &saved, sizeof(saved) };
        ptrace(PTRACE_SETREGSET, g_pid, (void *)NT_PRSTATUS, &sio);
    }
    ptrace(PTRACE_DETACH, g_pid, 0, 0);
    g_pid = 0;
    printf("injected\n");
    return 0;
}
