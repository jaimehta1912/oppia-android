package org.oppia.android.scripts.maven

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.oppia.android.scripts.common.CommandExecutorImpl
import org.oppia.android.scripts.proto.License
import org.oppia.android.scripts.proto.LocalCopyLink
import org.oppia.android.scripts.proto.MavenDependency
import org.oppia.android.scripts.proto.MavenDependencyList
import org.oppia.android.scripts.proto.ScrapableLink
import org.oppia.android.scripts.testing.TestBazelWorkspace
import org.oppia.android.testing.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** Tests for [GenerateMavenDependenciesList]. */
class GenerateMavenDependenciesListTest {

  private val DATA_BINDING_POM = "https://maven.google.com/androidx/databinding/" +
    "databinding-adapters/3.4.2/databinding-adapters-3.4.2.pom"
  private val PROTO_LITE_POM = "https://repo1.maven.org/maven2/com/google/protobuf/" +
    "protobuf-lite/3.0.0/protobuf-lite-3.0.0.pom"
  private val IO_FABRIC_POM = "https://maven.google.com/io/fabric/sdk/android/" +
    "fabric/1.4.7/fabric-1.4.7.pom"
  private val GLIDE_ANNOTATIONS_POM = "https://repo1.maven.org/maven2/com/github/" +
    "bumptech/glide/annotations/4.11.0/annotations-4.11.0.pom"
  private val FIREBASE_ANALYTICS_POM = "https://maven.google.com/com/google/firebase/" +
    "firebase-analytics/17.5.0/firebase-analytics-17.5.0.pom"

  private val THIRD_PARTY_PREFIX = "//third_pary:"
  private val DATA_BINDING_COORD = "androidx.databinding:databinding-adapters:3.4.2"
  private val PROTO_LITE_COORD = "com.google.protobuf:protobuf-lite:3.0.0"
  private val GLIDE_ANNOTATIONS_COORD = "com.github.bumptech.glide:annotations:4.11.0"
  private val FIREBASE_ANALYTICS_COORD = "com.google.firebase:firebase-analytics:17.5.0"
  private val IO_FABRIC_COORD = "io.fabric.sdk.android:fabric:1.4.7"

  private val DATA_BINDING_COORD_VERSION = "3.4.2"
  private val PROTO_LITE_COORD_VERSION = "3.0.0"
  private val GLIDE_ANNOTATIONS_COORD_VERSION = "4.11.0"
  private val FIREBASE_ANALYTICS_COORD_VERSION = "17.5.0"
  private val IO_FABRIC_COORD_VERSION = "1.4.7"

  private val LICENSE_DETAILS_INCOMPLETE_FAILURE = "Licenses details are not completed"
  private val UNAVAILABLE_OR_INVALID_LICENSE_LINKS_FAILURE =
    "License links are invalid or not available for some dependencies"
  private val SCRIPT_PASSED_MESSAGE =
    "Script executed succesfully: maven_dependencies.textproto updated successfully."

  private val outContent: ByteArrayOutputStream = ByteArrayOutputStream()
  private val originalOut: PrintStream = System.out

  private val mockLicenseFetcher by lazy { initializeLicenseFetcher() }
  private lateinit var testBazelWorkspace: TestBazelWorkspace

  @Rule
  @JvmField
  var tempFolder = TemporaryFolder()

  @Before
  fun setUp() {
    tempFolder.newFolder("scripts", "assets")
    tempFolder.newFolder("third_party")
    testBazelWorkspace = TestBazelWorkspace(tempFolder)
    System.setOut(PrintStream(outContent))
  }

  @After
  fun restoreStreams() {
    System.setOut(originalOut)
  }

  @Test
  fun testLicenseLinkNotVerified_scriptFailsWithException() {
    tempFolder.newFile("scripts/assets/maven_dependencies.pb")
    tempFolder.newFile("scripts/assets/maven_dependencies.textproto")

    val coordsList = listOf(DATA_BINDING_COORD, FIREBASE_ANALYTICS_COORD)
    setupBazelEnvironment(coordsList)

    val exception = assertThrows(Exception::class) {
      GenerateMavenDependenciesListRunner(
        mockLicenseFetcher,
        CommandExecutorImpl(600_000L)
      ).main(
        arrayOf(
          "${tempFolder.root}",
          "scripts/assets/maven_install.json",
          "${tempFolder.root}/scripts/assets/maven_dependencies.pb"
        )
      )
    }
    assertThat(exception).hasMessageThat().contains(LICENSE_DETAILS_INCOMPLETE_FAILURE)
  }

  @Test
  fun testDependencyDoesNotHaveAnyLicense_scriptFailsWithException() {
    tempFolder.newFile("scripts/assets/maven_dependencies.textproto")
    tempFolder.newFile("scripts/assets/maven_dependencies.pb")

    val coordsList = listOf(PROTO_LITE_COORD)
    setupBazelEnvironment(coordsList)

    val exception = assertThrows(Exception::class) {
      GenerateMavenDependenciesListRunner(
        mockLicenseFetcher,
        CommandExecutorImpl(600_000L)
      ).main(
        arrayOf(
          "${tempFolder.root}",
          "scripts/assets/maven_install.json",
          "${tempFolder.root}/scripts/assets/maven_dependencies.pb"
        )
      )
    }
    assertThat(exception).hasMessageThat().contains(UNAVAILABLE_OR_INVALID_LICENSE_LINKS_FAILURE)
  }

  @Test
  fun testDependencyHasInvalidLicenseLink_scriptFailsWithException() {
    tempFolder.newFile("scripts/assets/maven_dependencies.textproto")
    val pbFile = tempFolder.newFile("scripts/assets/maven_dependencies.pb")
    val license = License.newBuilder().apply {
      this.licenseName = "Fabric Terms of Service"
      this.originalLink = "https//:fabric.io.terms"
      this.isOriginalLinkInvalid = true
    }.build()
    val mavenDependencyList = MavenDependencyList.newBuilder().apply {
      this.addAllMavenDependency(
        listOf(
          MavenDependency.newBuilder().apply {
            this.artifactName = IO_FABRIC_COORD
            this.artifactVersion = IO_FABRIC_COORD_VERSION
            this.addAllLicense(listOf(license))
          }.build()
        )
      )
    }.build()
    mavenDependencyList.writeTo(pbFile.outputStream())

    val coordsList = listOf(IO_FABRIC_COORD)
    setupBazelEnvironment(coordsList)

    val exception = assertThrows(Exception::class) {
      GenerateMavenDependenciesListRunner(
        mockLicenseFetcher,
        CommandExecutorImpl(600_000L)
      ).main(
        arrayOf(
          "${tempFolder.root}",
          "scripts/assets/maven_install.json",
          "${tempFolder.root}/scripts/assets/maven_dependencies.pb"
        )
      )
    }
    assertThat(exception).hasMessageThat().contains(UNAVAILABLE_OR_INVALID_LICENSE_LINKS_FAILURE)
  }

  @Test
  fun testDependenciesHaveMultipleLicense_licenseDetailsCompleted_scriptPasses() {
    tempFolder.newFile("scripts/assets/maven_dependencies.textproto")
    val pbFile = tempFolder.newFile("scripts/assets/maven_dependencies.pb")
    val license1 = License.newBuilder().apply {
      this.licenseName = "The Apache License, Version 2.0"
      this.originalLink = "https://www.apache.org/licenses/LICENSE-2.0.txt"
      this.scrapableLink = ScrapableLink.newBuilder()
        .setUrl("https://www.apache.org/licenses/LICENSE-2.0.txt").build()
    }.build()
    val license2 = License.newBuilder().apply {
      this.licenseName = "Simplified BSD License"
      this.originalLink = "https://www.opensource.org/licenses/bsd-license"
      this.localCopyLink = LocalCopyLink.newBuilder()
        .setUrl("https://local-copy/bsd-license").build()
    }.build()
    val mavenDependencyList = MavenDependencyList.newBuilder().apply {
      this.addAllMavenDependency(
        listOf(
          MavenDependency.newBuilder().apply {
            this.artifactName = DATA_BINDING_COORD
            this.artifactVersion = DATA_BINDING_COORD_VERSION
            this.addAllLicense(listOf(license1))
          }.build(),
          MavenDependency.newBuilder().apply {
            this.artifactName = GLIDE_ANNOTATIONS_COORD
            this.artifactVersion = GLIDE_ANNOTATIONS_COORD_VERSION
            this.addAllLicense(listOf(license1, license2))
          }.build()
        )
      )
    }.build()
    mavenDependencyList.writeTo(pbFile.outputStream())

    val coordsList = listOf(DATA_BINDING_COORD, GLIDE_ANNOTATIONS_COORD)
    setupBazelEnvironment(coordsList)

    GenerateMavenDependenciesListRunner(
      mockLicenseFetcher,
      CommandExecutorImpl(600_000L)
    ).main(
      arrayOf(
        "${tempFolder.root}",
        "scripts/assets/maven_install.json",
        "${tempFolder.root}/scripts/assets/maven_dependencies.pb"
      )
    )
    assertThat(outContent.toString()).contains(SCRIPT_PASSED_MESSAGE)
  }

  @Test
  fun testDependenciesHaveCompleteLicenseDetails_scriptPasses() {
    tempFolder.newFile("scripts/assets/maven_dependencies.textproto")
    val pbFile = tempFolder.newFile("scripts/assets/maven_dependencies.pb")
    val license1 = License.newBuilder().apply {
      this.licenseName = "The Apache License, Version 2.0"
      this.originalLink = "https://www.apache.org/licenses/LICENSE-2.0.txt"
      this.scrapableLink = ScrapableLink.newBuilder()
        .setUrl("https://www.apache.org/licenses/LICENSE-2.0.txt").build()
    }.build()
    val license2 = License.newBuilder().apply {
      this.licenseName = "Simplified BSD License"
      this.originalLink = "https://www.opensource.org/licenses/bsd-license"
      this.localCopyLink = LocalCopyLink.newBuilder()
        .setUrl("https://local-copy/bsd-license").build()
    }.build()
    val mavenDependencyList = MavenDependencyList.newBuilder().apply {
      this.addAllMavenDependency(
        listOf(
          MavenDependency.newBuilder().apply {
            this.artifactName = DATA_BINDING_COORD
            this.artifactVersion = DATA_BINDING_COORD_VERSION
            this.addAllLicense(listOf(license1))
          }.build(),
          MavenDependency.newBuilder().apply {
            this.artifactName = PROTO_LITE_COORD
            this.artifactVersion = PROTO_LITE_COORD_VERSION
            this.addAllLicense(listOf(license2))
          }.build()
        )
      )
    }.build()
    mavenDependencyList.writeTo(pbFile.outputStream())

    val coordsList = listOf(DATA_BINDING_COORD, PROTO_LITE_COORD)
    setupBazelEnvironment(coordsList)

    GenerateMavenDependenciesListRunner(
      mockLicenseFetcher,
      CommandExecutorImpl(600_000L)
    ).main(
      arrayOf(
        "${tempFolder.root}",
        "scripts/assets/maven_install.json",
        "${tempFolder.root}/scripts/assets/maven_dependencies.pb"
      )
    )
    assertThat(outContent.toString()).contains(SCRIPT_PASSED_MESSAGE)
  }

  private fun setupBazelEnvironment(coordsList: List<String>) {
    val mavenInstallJson = tempFolder.newFile("scripts/assets/maven_install.json")
    writeMavenInstallJson(mavenInstallJson)
    testBazelWorkspace.ensureWorkspaceIsConfiguredForRulesJvmExternal(coordsList)
    val thirdPartyPrefixCoordList = coordsList.map { coordinate ->
      "//third_party:${omitVersionAndReplaceColonsHyphensPeriods(coordinate)}"
    }
    createAndroidBinary(thirdPartyPrefixCoordList)
    writeThirdPartyBuildFile(coordsList)
  }

  private fun writeThirdPartyBuildFile(exportsList: List<String>) {
    val thirdPartyBuild = tempFolder.newFile("third_party/BUILD.bazel")
    thirdPartyBuild.appendText(
      """
      load("@rules_jvm_external//:defs.bzl", "artifact")
      """.trimIndent() + "\n"
    )
    for (export in exportsList) {
      createAndroidLibrary(thirdPartyBuild, export)
    }
  }

  private fun createAndroidLibrary(thirdPartyBuild: File, artifactName: String) {
    thirdPartyBuild.appendText(
      """
      android_library(
          name = "${omitVersionAndReplaceColonsHyphensPeriods(artifactName)}",
          visibility = ["//visibility:public"],
          exports = [artifact("$artifactName")],
      )
      """.trimIndent() + "\n"
    )
  }

  private fun omitVersionAndReplaceColonsHyphensPeriods(artifactName: String): String {
    val lastColonIndex = artifactName.lastIndexOf(':')
    return artifactName.substring(0, lastColonIndex).replace('.', '_').replace(':', '_')
  }

  fun createAndroidBinary(
    dependenciesList: List<String>
  ) {
    tempFolder.newFile("test_manifest.xml")
    val build = tempFolder.newFile("BUILD.bazel")
    build.appendText("depsList = [\n")
    for (dep in dependenciesList) {
      build.appendText("\"$dep\",")
    }
    build.appendText("]\n")
    build.appendText(
      """
      android_binary(
          name = "oppia",
          manifest = "test_manifest.xml",
          deps = depsList
      )
      """.trimIndent() + "\n"
    )
  }

  private fun writeMavenInstallJson(file: File) {
    file.writeText(
      """
      {
        "dependency_tree": {
          "dependencies": [
            {
              "coord": "androidx.databinding:databinding-adapters:3.4.2",
              "url": "https://maven.google.com/androidx/databinding/databinding-adapters/3.4.2/databinding-adapters-3.4.2.aar"
            },
            {
              "coord": "com.github.bumptech.glide:annotations:4.11.0",
              "url": "https://repo1.maven.org/maven2/com/github/bumptech/glide/annotations/4.11.0/annotations-4.11.0.jar"
            },
            {
              "coord": "com.google.firebase:firebase-analytics:17.5.0",
              "url": "https://maven.google.com/com/google/firebase/firebase-analytics/17.5.0/firebase-analytics-17.5.0.aar"
            },
            {
               "coord": "com.google.protobuf:protobuf-lite:3.0.0",
               "url": "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-lite/3.0.0/protobuf-lite-3.0.0.jar"
            },
            {
              "coord": "io.fabric.sdk.android:fabric:1.4.7",
              "url": "https://maven.google.com/io/fabric/sdk/android/fabric/1.4.7/fabric-1.4.7.aar"
            }
          ]
        }
      }  
      """.trimIndent()
    )
  }

  private fun initializeLicenseFetcher(): LicenseFetcher {
    return mock<LicenseFetcher> {
      on { scrapeText(eq(DATA_BINDING_POM)) }
        .doReturn(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <licenses>
            <license>
              <name>The Apache Software License, Version 2.0</name>
              <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          """.trimIndent()
        )
      on { scrapeText(eq(GLIDE_ANNOTATIONS_POM)) }
        .doReturn(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <licenses>
            <license>
              <name>The MIT License</name>
              <url>https://opensource.org/licenses/MIT</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          """.trimIndent()
        )
      on { scrapeText(eq(FIREBASE_ANALYTICS_POM)) }
        .doReturn(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <licenses>
            <license>
              <name>Android Software Development Kit License</name>
              <url>https://developer.android.com/studio/terms.html</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          """.trimIndent()
        )
      on { scrapeText(eq(IO_FABRIC_POM)) }
        .doReturn(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <licenses>
            <license>
              <name>Fabric Terms of Service</name>
              <url>https://www.fabric.io.terms</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          """.trimIndent()
        )
      on { scrapeText(eq(PROTO_LITE_POM)) }
        .doReturn(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <project>Random Project</project>
          """.trimIndent()
        )
    }
  }
}