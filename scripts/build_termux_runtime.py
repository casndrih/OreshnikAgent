#!/usr/bin/env python3
"""Build a relocatable ARM64 runtime from the authenticated Termux APT index."""
from __future__ import annotations
import argparse, hashlib, json, lzma, re, shutil, subprocess, tempfile, urllib.request, zipfile
from pathlib import Path

PREFIX = "data/data/com.termux/files/usr"
PACKAGES = ["bash","busybox","coreutils","findutils","grep","sed","gawk","git","openssh","curlie","libcurl","wget","jq","ripgrep","libsqlite","libmagic","openssl","rsync","inetutils","dnsutils","iproute2","net-tools","nmap","procps","psmisc","diffutils","tar","gzip","zip","unzip","tree","make"]
ENTRYPOINTS = ["bash","busybox","sh","env","git","ssh","scp","sftp","curl","curlie","wget","jq","rg","sqlite3","openssl","rsync","nmap","ping","traceroute","dig","nslookup","ifconfig","netstat","ip","ss","tar","gzip","gunzip","zip","unzip","find","grep","sed","awk","gawk","diff","cmp","tree","file","head","tail","sort","uniq","cut","tr","wc","make"]
UA = "OreshnikAgent-TermuxRuntimeBuilder/1.0"

def download(url: str, target: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=90) as response, target.open("wb") as out:
        shutil.copyfileobj(response, out)

def fields(block: str) -> dict[str,str]:
    result, current = {}, None
    for line in block.splitlines():
        if line.startswith((" ","\t")) and current:
            result[current] += "\n" + line[1:]
        elif ":" in line:
            current, value = line.split(":",1)
            result[current] = value.strip()
    return result

def parse_index(raw: bytes) -> dict[str,dict[str,str]]:
    content = lzma.decompress(raw).decode("utf-8","replace")
    result = {}
    for block in re.split(r"\n\s*\n",content):
        if not block.strip(): continue
        item = fields(block)
        if item.get("Package") and item.get("Architecture") in ("aarch64","all"):
            result[item["Package"]] = item
    return result

def dependency_groups(value: str) -> list[list[str]]:
    groups=[]
    for clause in value.split(","):
        clause=re.sub(r"\[[^\]]*\]|\([^\)]*\)|<[^>]*>","",clause)
        names=[]
        for alternative in clause.split("|"):
            m=re.match(r"\s*([a-zA-Z0-9][a-zA-Z0-9+.-]*)(?::[a-zA-Z0-9_-]+)?",alternative)
            if m: names.append(m.group(1))
        if names: groups.append(names)
    return groups

def resolve(requested, records):
    providers={}
    for name,item in records.items():
        for value in item.get("Provides","").split(","):
            m=re.match(r"\s*([a-zA-Z0-9][a-zA-Z0-9+.-]*)",value)
            if m: providers.setdefault(m.group(1),name)
    seen, ordered=set(),[]
    def add(name):
        name=providers.get(name,name)
        if name in seen: return
        if name not in records: raise RuntimeError("Missing Termux dependency: "+name)
        seen.add(name); item=records[name]
        for field in ("Pre-Depends","Depends"):
            for alternatives in dependency_groups(item.get(field,"")):
                dep=next((x for x in alternatives if x in records),None)
                if dep is None: dep=next((providers[x] for x in alternatives if x in providers),None)
                if dep is None: raise RuntimeError(f"Unresolved dependency {alternatives} required by {name}")
                add(dep)
        ordered.append(name)
    for name in requested: add(name)
    return ordered

def authenticated_index(repo, inrelease, work):
    text=inrelease.read_text(encoding="utf-8")
    if "-----BEGIN PGP SIGNED MESSAGE-----" not in text or "-----BEGIN PGP SIGNATURE-----" not in text:
        raise RuntimeError("InRelease is not clear-signed")
    body=text.split("\n\n",1)[1].split("-----BEGIN PGP SIGNATURE-----",1)[0]
    hashes={}; active=False
    for line in body.splitlines():
        if line.strip()=="SHA256:": active=True; continue
        if active:
            m=re.match(r"\s*([a-fA-F0-9]{64})\s+\d+\s+(\S+)\s*$",line)
            if m: hashes[m.group(2)]=m.group(1).lower()
            elif line and not line[0].isspace(): break
    path=next((p for p in ("main/binary-aarch64/Packages.xz","main/binary-aarch64/Packages.gz","main/binary-aarch64/Packages") if p in hashes),None)
    if not path: raise RuntimeError("Signed InRelease has no ARM64 package index")
    index=work/Path(path).name
    download(repo.rstrip("/")+"/dists/stable/"+path,index)
    actual=hashlib.sha256(index.read_bytes()).hexdigest()
    if actual!=hashes[path]: raise RuntimeError("Packages index hash differs from signed InRelease")
    raw=index.read_bytes()
    if path.endswith(".gz"):
        import gzip
        raw=lzma.compress(gzip.decompress(raw))
    elif not path.endswith(".xz"):
        raw=lzma.compress(raw)
    return parse_index(raw),actual,text

def patch_shebangs(root: Path):
    prefix="/data/data/com.termux/files/usr"
    for path in root.rglob("*"):
        if not path.is_file(): continue
        try:
            with path.open("rb") as stream:
                first=stream.readline(8192)
                if not first.startswith(b"#!") or b"\\0" in first: continue
                rest=stream.read()
            line=first.decode("utf-8","strict").rstrip("\r\n")
            if line.startswith("#!/usr/bin/env "):
                command=line[len("#!/usr/bin/env "):].split(" ",1)[0]
                if command == "sh":
                    line="#!/system/bin/sh"+line[len("#!/usr/bin/env sh"):]
                else:
                    line="#!@RUNTIME_PREFIX@/bin/env "+line[len("#!/usr/bin/env "):]
            elif line.startswith(("#!/bin/bash","#!/usr/bin/bash","#!"+prefix+"/bin/bash")):
                pos=line.find("bash"); line="#!@RUNTIME_PREFIX@/bin/bash"+line[pos+4:]
            elif line.startswith(("#!/bin/sh","#!/usr/bin/sh","#!"+prefix+"/bin/sh")):
                pos=line.find("sh"); line="#!/system/bin/sh"+line[pos+2:]
            else:
                line=line.replace(prefix,"@RUNTIME_PREFIX@")
            path.write_bytes((line+"\n").encode()+rest)
        except (UnicodeDecodeError,OSError): pass

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--repo",default="https://packages.termux.dev/apt/termux-main")
    ap.add_argument("--inrelease",type=Path,required=True)
    ap.add_argument("--output",type=Path,required=True)
    args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix="oreshnik-termux-") as temp:
        work=Path(temp); records,index_sha,release_text=authenticated_index(args.repo,args.inrelease,work)
        resolved=resolve(PACKAGES,records)
        print("Resolved packages:",", ".join(resolved),flush=True)
        debs=work/"debs"; extract=work/"extract"; stage=work/"termux-arm64"
        debs.mkdir(); extract.mkdir(); stage.mkdir()
        metadata=[]
        for i,name in enumerate(resolved,1):
            item=records[name]; filename=item.get("Filename",""); expected=item.get("SHA256","").lower()
            if not filename or not re.fullmatch(r"[a-f0-9]{64}",expected):
                raise RuntimeError(f"Incomplete package metadata: {name}")
            deb=debs/(name+".deb")
            print(f"[{i}/{len(resolved)}] {name} {item.get('Version','')}",flush=True)
            download(args.repo.rstrip("/")+"/"+filename,deb)
            digest=hashlib.sha256(deb.read_bytes()).hexdigest()
            if digest!=expected: raise RuntimeError("Package SHA-256 mismatch: "+name)
            subprocess.run(["dpkg-deb","-x",str(deb),str(extract)],check=True)
            metadata.append({"name":name,"version":item.get("Version",""),"architecture":item.get("Architecture",""),"filename":filename,"sha256":digest})
        source=extract/PREFIX
        if not source.is_dir(): raise RuntimeError("Expected Termux prefix absent: "+str(source))
        # Some Termux packages ship dangling optional service/doc symlinks. Drop only
        # links whose targets are absent from this dependency-closed extraction; all
        # resolvable links are dereferenced into ordinary files by rsync below.
        dangling=0
        for link in source.rglob("*"):
            if link.is_symlink() and not link.exists():
                link.unlink(); dangling+=1
        print(f"Removed {dangling} dangling package symlinks before relocation",flush=True)
        subprocess.run(["rsync","-aL","--safe-links",str(source)+"/",str(stage)+"/"],check=True)
        patch_shebangs(stage)
        entrypoints={name:f"bin/{name}" for name in ENTRYPOINTS if (stage/"bin"/name).is_file()}
        if "curl" not in entrypoints and "curlie" in entrypoints:
            entrypoints["curl"] = entrypoints["curlie"]
        if not all(x in entrypoints for x in ("bash","git","curl")):
            raise RuntimeError("Missing core tools after extraction: "+repr(sorted(entrypoints)))
        date=re.search(r"(?m)^Date:\s*(.+)$",release_text)
        date_label=re.sub(r"[^0-9A-Za-z.-]","-",date.group(1).strip()) if date else "rolling"
        version=f"{date_label}-{index_sha[:12]}"
        manifest={"id":"termux-arm64","version":version,"entrypoints":entrypoints,"source":"https://packages.termux.dev/apt/termux-main","architecture":"aarch64","packageIndexSha256":index_sha,"packages":metadata}
        (stage/"runtime.json").write_text(json.dumps(manifest,indent=2)+"\n")
        (stage/"PACKAGE-METADATA.json").write_text(json.dumps(metadata,indent=2)+"\n")
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(args.output,"w",zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as archive:
            for path in sorted(stage.rglob("*")):
                if path.is_file(): archive.write(path,path.relative_to(stage).as_posix())
        digest=hashlib.sha256(args.output.read_bytes()).hexdigest()
        args.output.with_suffix(args.output.suffix+".sha256").write_text(f"{digest}  {args.output.name}\n")
        print(f"Built {args.output}: {args.output.stat().st_size} bytes, sha256={digest}",flush=True)
        print("Entrypoints:",json.dumps(entrypoints,sort_keys=True),flush=True)

if __name__=="__main__": main()
