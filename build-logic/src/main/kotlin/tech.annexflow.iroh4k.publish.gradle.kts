import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
}

/**
 * The project's home. Referenced by the POM's url, license and scm entries, so it is declared once
 * here rather than repeated four times.
 */
val projectUrl = "https://github.com/annexflow/iroh4k"

extensions.configure<MavenPublishBaseExtension> {
    // Publishes every enabled Kotlin target plus the shared metadata, so what reaches Maven
    // Central is exactly what `-Ptargets` built. A publish therefore has to run with
    // `-Ptargets=all`, or the artifact will silently be missing platforms.
    configure(KotlinMultiplatform(sourcesJar = SourcesJar.Sources()))

    coordinates(
        groupId = project.group as String,
        artifactId = project.name,
        version = project.version as String,
    )

    pom {
        name.set(project.name)
        description.set(
            "Kotlin Multiplatform bindings for iroh: peer-to-peer QUIC connections dialled by " +
                "public key, on JVM, Android, iOS, macOS, Linux and Windows."
        )
        url.set(projectUrl)
        // Dual licensed, as iroh and iroh-ffi are: a consumer takes whichever of the two suits
        // them, and picks up nothing stricter than upstream. Two `license` entries with no
        // `distribution` set is how Maven expresses "or", which is what the POM schema gives us —
        // the authoritative statement is the SPDX expression `Apache-2.0 OR MIT` in the README.
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("$projectUrl/blob/main/LICENSE-APACHE")
            }
            license {
                name.set("MIT License")
                url.set("$projectUrl/blob/main/LICENSE-MIT")
            }
        }
        developers {
            developer {
                id.set("annexflow")
                name.set("Annexflow")
            }
        }
        scm {
            url.set(projectUrl)
            connection.set("scm:git:$projectUrl.git")
            developerConnection.set("scm:git:$projectUrl.git")
        }
    }

    // Credentials come from the plugin's standard Gradle properties or environment variables —
    // ORG_GRADLE_PROJECT_mavenCentralUsername / …Password / …signingInMemoryKey — so nothing
    // secret is committed here, and a publish without them fails rather than publishing unsigned.
    publishToMavenCentral()
    signAllPublications()
}
