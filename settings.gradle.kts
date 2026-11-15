// Clear system property and env var for ANDROID_PREFS_ROOT to avoid AGP AndroidLocationsException
System.clearProperty("ANDROID_PREFS_ROOT")
try {
    val processEnvClass = Class.forName("java.lang.ProcessEnvironment")
    val fields = arrayOf("theEnvironment", "theCaseInsensitiveEnvironment", "theUnmodifiableEnvironment")
    for (fieldName in fields) {
        try {
            val field = processEnvClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val map = field.get(null)
            if (map is MutableMap<*, *>) {
                map.remove("ANDROID_PREFS_ROOT")
            }
        } catch (_: Throwable) {}
    }
} catch (_: Throwable) {}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Oreshik"
include(":app")