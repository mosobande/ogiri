/**
 * Applies one lazy version provider to every project.
 *
 * Precedence: `RELEASE_VERSION` environment variable, `-PRELEASE_VERSION` Gradle property,
 * `.ogiri-version`, then `0.0.0-SNAPSHOT`.
 */
val versionFile = rootProject.layout.projectDirectory.file(".ogiri-version").asFile
val fileVersion =
    providers.provider {
      versionFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
    }
val resolvedVersion =
    providers.environmentVariable("RELEASE_VERSION")
        .orElse(providers.gradleProperty("RELEASE_VERSION"))
        .orElse(fileVersion)
        .orElse("0.0.0-SNAPSHOT")

rootProject.allprojects { version = resolvedVersion.get() }
