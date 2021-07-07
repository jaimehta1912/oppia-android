package org.oppia.android.scripts.maven

import com.google.protobuf.MessageLite
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.oppia.android.scripts.maven.maveninstall.MavenListDependency
import org.oppia.android.scripts.maven.maveninstall.MavenListDependencyTree
import org.oppia.android.scripts.proto.License
import org.oppia.android.scripts.proto.MavenDependency
import org.oppia.android.scripts.proto.MavenDependencyList
import org.oppia.android.scripts.proto.OriginOfLicenses
import org.oppia.android.scripts.proto.PrimaryLinkType
import org.oppia.android.scripts.proto.SecondaryLinkType
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val WAIT_PROCESS_TIMEOUT_MS = 60_000L
private const val LICENSES_TAG = "<licenses>"
private const val LICENSES_CLOSE_TAG = "</licenses>"
private const val LICENSE_TAG = "<license>"
private const val NAME_TAG = "<name>"
private const val URL_TAG = "<url>"

var backupLicenseDepsList: MutableList<String> = mutableListOf<String>()

var bazelQueryDepsNames: MutableList<String> = mutableListOf<String>()
var mavenInstallDependencyList: MutableList<MavenListDependency>? =
  mutableListOf<MavenListDependency>()
var finalDependenciesList = mutableListOf<MavenListDependency>()
var parsedArtifactsList = mutableListOf<String>()

val linksSet = mutableSetOf<String>()
val noLicenseSet = mutableSetOf<String>()

private var countInvalidPomUrl = 0
var countDepsWithoutLicenseLinks = 0

var scriptFailed = false

var rootPath: String = ""

fun printMessage(message: String) {
  println(
    """*********************
    $message  
    *********************
    """.trimIndent()
  )
}

/**
 * Usage:
 */
fun main(args: Array<String>) {
  if (args.size < 3) {
    throw Exception("Too less arguments passed.")
  }
  val pathToRoot = args[0]
  val pathToMavenInstall = args[1]
  val pathToMavenDependenciesTextProto = args[2]
  runMavenRePinCommand(pathToRoot)
  runBazelQueryCommand(pathToRoot)
  readMavenInstall(pathToMavenInstall)

  val dependenciesListFromTextproto = retrieveMavenDependencyList()
  val dependenciesListFromPom = getLicenseLinksFromPOM().mavenDependencyListList

  val licenseSetFromTextproto = mutableSetOf<License>()
  val licenseSetFromPom = mutableSetOf<License>()

  dependenciesListFromTextproto.forEach { dependency ->
    dependency.licenseList.forEach {
      licenseSetFromTextproto.add(it)
    }
  }

  dependenciesListFromPom.forEach { dependency ->
    dependency.licenseList.forEach {
      licenseSetFromPom.add(it)
    }
  }

  val finalLicensesSet = updateLicensesSet(
    licenseSetFromTextproto,
    licenseSetFromTextproto
  )

  val finalDependenciesList = updateMavenDependenciesList(
    dependenciesListFromPom,
    finalLicensesSet
  )
  writeTextProto(
    pathToMavenDependenciesTextProto,
    MavenDependencyList.newBuilder().addAllMavenDependencyList(finalDependenciesList).build()
  )

  val licensesToBeFixed = getAllBrokenLicenses(finalDependenciesList)
  if (licensesToBeFixed.isNotEmpty()) {
    println("Please provide all the details of the following licenses manually:")
    licensesToBeFixed.forEach {
      println("\nlicense_name: ${it.licenseName}")
      println("primary_link: ${it.primaryLink}")
      println("primary_link_type: ${it.primaryLinkType}")
      println("secondary_link: ${it.secondaryLink}")
      println("secondary_link_type: ${it.secondaryLinkType}")
      println("secondary_license_name: ${it.secondaryLicenseName}\n")
    }
    throw Exception("Licenses details are not completed.")
  }

  val dependenciesWithoutAnyLinks = getDependenciesWithNoAndInvalidLinks(finalDependenciesList)
  if (dependenciesWithoutAnyLinks.isNotEmpty()) {
    println("Please provide the license links for the following dependencies manually:")
    dependenciesWithoutAnyLinks.forEach {
      println(it)
    }
    throw Exception(
      """
      There does not exist any license links (or the extracted license links are invalid) 
      for some dependencies.
      """.trimIndent()
    )
  }

  val dependenciesThatNeedHumanIntervention = getDependenciesThatNeedHumanIntervention(
    finalDependenciesList
  )
  if (dependenciesThatNeedHumanIntervention.isNotEmpty()) {
    println(
      """There are still some dependencies that need human intervention.
      Try to find the license links for these dependencies and coordinate with the Oppia-android maintainers
      to fix the issue.
      """.trimIndent()
    )
    throw Exception("Human Intervention needed.")
  }
  println("Maven Dependencies updated successfully.")
}

fun updateLicensesSet(
  licenseSetFromTextproto: Set<License>,
  licenseSetFromPom: Set<License>
): Set<License> {
  val finalLicensesSet = mutableSetOf<License>()
  licenseSetFromPom.forEach { license ->
    val updatedLicense = licenseSetFromTextproto.find { it.primaryLink == license.primaryLink }
    if (updatedLicense != null) {
      finalLicensesSet.add(updatedLicense)
    } else {
      finalLicensesSet.add(license)
    }
  }
  return finalLicensesSet
}

fun getDependenciesThatNeedHumanIntervention(
  mavenDependenciesList: List<MavenDependency>
): Set<MavenDependency> {
  val dependencies = mutableSetOf<MavenDependency>()
  mavenDependenciesList.forEach { dependency ->
    dependency.licenseList.forEach { license ->
      if (license.primaryLinkType == PrimaryLinkType.NEEDS_INTERVENTION) {
        dependencies.add(dependency)
      }
    }
  }
  return dependencies
}

fun getDependenciesWithNoAndInvalidLinks(
  mavenDependenciesList: List<MavenDependency>
): Set<MavenDependency> {
  val dependenciesWithoutLicenses = mutableSetOf<MavenDependency>()
  mavenDependenciesList.forEach {
    if (it.licenseList.isEmpty()) {
      dependenciesWithoutLicenses.add(it)
    } else {
      it.licenseList.forEach { license ->
        if (license.primaryLinkType == PrimaryLinkType.INVALID_LINK) {
          dependenciesWithoutLicenses.add(it)
        }
      }
    }
  }
  return dependenciesWithoutLicenses
}

fun getAllBrokenLicenses(
  mavenDependenciesList: List<MavenDependency>
): Set<License> {
  val licenseSet = mutableSetOf<License>()
  mavenDependenciesList.forEach { dependency ->
    dependency.licenseList.forEach { license ->
      if (
        license.primaryLinkType == PrimaryLinkType.PRIMARY_LINK_TYPE_UNSPECIFIED ||
        license.primaryLinkType == PrimaryLinkType.UNRECOGNIZED
      ) {
        licenseSet.add(license)
      } else if (
        (
          license.primaryLinkType == PrimaryLinkType.SCRAPE_FROM_LOCAL_COPY ||
            license.primaryLinkType == PrimaryLinkType.NEEDS_INTERVENTION
          ) &&
        (
          license.secondaryLink.isEmpty() ||
            license.secondaryLinkType == SecondaryLinkType.UNRECOGNIZED ||
            license.secondaryLinkType == SecondaryLinkType.SECONDARY_LINK_TYPE_UNSPECIFIED ||
            license.secondaryLicenseName.isEmpty()
          )
      ) {
        licenseSet.add(license)
      }
    }
  }
  return licenseSet
}

fun updateMavenDependenciesList(
  latestDependenciesList: List<MavenDependency>,
  finalLicensesSet: Set<License>
): MutableList<MavenDependency> {
  val finalUpdatedList = mutableListOf<MavenDependency>()

  latestDependenciesList.forEach { mavenDependency ->
    val updateLicenseList = mutableListOf<License>()
    var numberOfLicensesToBeProvidedManually = 0
    mavenDependency.licenseList.forEach { license ->
      val updatedLicense = finalLicensesSet.find { it.primaryLink == license.primaryLink }
      if (updatedLicense != null) {
        updateLicenseList.add(updatedLicense)
        if (updatedLicense.primaryLinkType == PrimaryLinkType.NEEDS_INTERVENTION ||
          updatedLicense.primaryLinkType == PrimaryLinkType.INVALID_LINK
        ) {
          numberOfLicensesToBeProvidedManually++
        }
      } else {
        if (license.primaryLinkType == PrimaryLinkType.NEEDS_INTERVENTION ||
          license.primaryLinkType == PrimaryLinkType.INVALID_LINK
        ) {
          numberOfLicensesToBeProvidedManually++
        }
        updateLicenseList.add(license)
      }
    }
    val dependency = MavenDependency.newBuilder()
      .setArtifactName(mavenDependency.artifactName)
      .setArtifactVersion(mavenDependency.artifactVersion)
      .addAllLicense(updateLicenseList)
      .setOriginOfLicense(OriginOfLicenses.UNKNOWN)
    if (numberOfLicensesToBeProvidedManually == updateLicenseList.size &&
      updateLicenseList.isNotEmpty()
    ) {
      dependency.originOfLicense = OriginOfLicenses.MANUAL
    } else if (numberOfLicensesToBeProvidedManually != updateLicenseList.size &&
      updateLicenseList.isNotEmpty()
    ) {
      dependency.originOfLicense = OriginOfLicenses.PARTIALLY_FROM_POM
    } else if (numberOfLicensesToBeProvidedManually == 0 && updateLicenseList.isNotEmpty()) {
      dependency.originOfLicense = OriginOfLicenses.ENTIRELY_FROM_POM
    }
    finalUpdatedList.add(dependency.build())
  }
  return finalUpdatedList
}

/**
 * Retrieves all file content checks.
 *
 * @return a list of all the FileContentChecks
 */
private fun retrieveMavenDependencyList(): List<MavenDependency> {
  return getProto(
    "maven_dependencies.pb",
    MavenDependencyList.getDefaultInstance()
  ).mavenDependencyListList.toList()
}

/**
 * Helper function to parse the textproto file to a proto class.
 *
 * @param textProtoFileName name of the textproto file to be parsed
 * @param proto instance of the proto class
 * @return proto class from the parsed textproto file
 */
private fun <T : MessageLite> getProto(textProtoFileName: String, proto: T): T {
  val protoBinaryFile = File("scripts/assets/$textProtoFileName")
  val builder = proto.newBuilderForType()

  // This cast is type-safe since proto guarantees type consistency from mergeFrom(),
  // and this method is bounded by the generic type T.
  @Suppress("UNCHECKED_CAST")
  val protoObj: T =
    FileInputStream(protoBinaryFile).use {
      builder.mergeFrom(it)
    }.build() as T
  return protoObj
}

fun parseArtifactName(artifactName: String): String {
  var colonIndex = artifactName.length - 1
  while (artifactName.isNotEmpty() && artifactName[colonIndex] != ':') {
    colonIndex--
  }
  val artifactNameWithoutVersion = artifactName.substring(0, colonIndex)
  val parsedArtifactNameBuilder = StringBuilder()
  for (index in artifactNameWithoutVersion.indices) {
    if (artifactNameWithoutVersion[index] == '.' || artifactNameWithoutVersion[index] == ':' ||
      artifactNameWithoutVersion[index] == '-'
    ) {
      parsedArtifactNameBuilder.append(
        '_'
      )
    } else {
      parsedArtifactNameBuilder.append(artifactNameWithoutVersion[index])
    }
  }
  return parsedArtifactNameBuilder.toString()
}

fun runBazelQueryCommand(rootPath: String) {
  val rootDirectory = File(rootPath).absoluteFile
  val bazelClient = BazelClient(rootDirectory)
  val output = bazelClient.executeBazelCommand(
    "query",
    "\'deps(deps(//:oppia)",
    "intersect",
    "//third_party/...)",
    "intersect",
    "@maven//...\'"
  )
  output.forEach { dep ->
    bazelQueryDepsNames.add(dep.substring(9, dep.length))
  }
  bazelQueryDepsNames.sort()
}

private fun readMavenInstall(pathToMavenInstall: String) {
  val mavenInstallJson = File(pathToMavenInstall)
  val mavenInstallJsonText = mavenInstallJson.inputStream().bufferedReader().use { it.readText() }
  val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  val adapter = moshi.adapter(MavenListDependencyTree::class.java)
  val dependencyTree = adapter.fromJson(mavenInstallJsonText)
  mavenInstallDependencyList = dependencyTree?.mavenListDependencies?.dependencyList
  mavenInstallDependencyList?.sortBy { it -> it.coord }

  mavenInstallDependencyList?.forEach { dep ->
    val artifactName = dep.coord
    val parsedArtifactName = parseArtifactName(artifactName)
    if (bazelQueryDepsNames.contains(parsedArtifactName)) {
      parsedArtifactsList.add(parsedArtifactName)
      finalDependenciesList.add(dep)
    }
  }
}

private fun getLicenseLinksFromPOM(): MavenDependencyList {
  var index = 0
  val mavenDependencyList = arrayListOf<MavenDependency>()
  finalDependenciesList.forEach {
    val url = it.url
    val pomFileUrl = url?.substring(0, url.length - 3) + "pom"
    val artifactName = it.coord
    val artifactVersion = StringBuilder()
    var lastIndex = artifactName.length - 1
    while (lastIndex >= 0 && artifactName[lastIndex] != ':') {
      artifactVersion.append(artifactName[lastIndex])
      lastIndex--
    }
    artifactVersion.reverse()
    val licenseNamesFromPom = mutableListOf<String>()
    val licenseLinksFromPom = mutableListOf<String>()
    val licenseList = arrayListOf<License>()
    try {
      val pomFile = URL(pomFileUrl).openStream().bufferedReader().readText()
      val pomText = pomFile
      var cursor = -1
      if (pomText.length > 11) {
        for (index in 0..(pomText.length - 11)) {
          if (pomText.substring(index, index + 10) == LICENSES_TAG) {
            cursor = index + 9
            break
          }
        }
        if (cursor != -1) {
          var cursor2 = cursor
          while (cursor2 < (pomText.length - 12)) {
            if (pomText.substring(cursor2, cursor2 + 9) == LICENSE_TAG) {
              cursor2 += 9
              while (cursor2 < pomText.length - 6 &&
                pomText.substring(
                  cursor2,
                  cursor2 + 6
                ) != NAME_TAG
              ) {
                ++cursor2
              }
              cursor2 += 6
              val url = StringBuilder()
              val urlName = StringBuilder()
              while (pomText[cursor2] != '<') {
                urlName.append(pomText[cursor2])
                ++cursor2
              }
              while (cursor2 < pomText.length - 4 &&
                pomText.substring(
                  cursor2,
                  cursor2 + 5
                ) != URL_TAG
              ) {
                ++cursor2
              }
              cursor2 += 5
              while (pomText[cursor2] != '<') {
                url.append(pomText[cursor2])
                ++cursor2
              }
              licenseNamesFromPom.add(urlName.toString())
              licenseLinksFromPom.add(url.toString())
              licenseList.add(
                License
                  .newBuilder()
                  .setLicenseName(urlName.toString())
                  .setPrimaryLink(url.toString())
                  .setPrimaryLinkType(PrimaryLinkType.PRIMARY_LINK_TYPE_UNSPECIFIED)
                  .build()
              )
              linksSet.add(url.toString())
            } else if (pomText.substring(cursor2, cursor2 + 12) == LICENSES_CLOSE_TAG) {
              break
            }
            ++cursor2
          }
        }
      }
    } catch (e: Exception) {
      ++countInvalidPomUrl
      scriptFailed = true
      val message =
        """Error : There was a problem while opening the provided link  -
        URL : $pomFileUrl")
        MavenListDependency Name : $artifactName
        """.trimIndent()
      printMessage(message)
      e.printStackTrace()
      exitProcess(1)
    }
    val mavenDependency = MavenDependency
      .newBuilder()
      .setIndex(index++)
      .setArtifactName(it.coord)
      .setArtifactVersion(artifactVersion.toString())
      .addAllLicense(licenseList)
      .setOriginOfLicenseValue(OriginOfLicenses.UNKNOWN_VALUE)

    mavenDependencyList.add(mavenDependency.build())
  }
  return MavenDependencyList.newBuilder().addAllMavenDependencyList(mavenDependencyList).build()
}

fun writeTextProto(
  pathToTextProto: String,
  mavenDependencyList: MavenDependencyList
) {
  val file = File(pathToTextProto)
  val list = mavenDependencyList.toString()

  file.printWriter().use { out ->
    out.println(list)
  }
}

fun runMavenRePinCommand(rootPath: String) {
  val rootDirectory = File(rootPath).absoluteFile
  val bazelClient = BazelClient(rootDirectory)
  val output = bazelClient.executeBazelRePinCommand(
    "bazel",
    "run",
    "@unpinned_maven//:pin"
  )
  println(output)
}

private class BazelClient(private val rootDirectory: File) {
  fun executeBazelCommand(
    vararg arguments: String,
    allowPartialFailures: Boolean = false
  ): List<String> {
    val result =
      executeCommand(rootDirectory, command = "bazel", *arguments, includeErrorOutput = false)
    // Per https://docs.bazel.build/versions/main/guide.html#what-exit-code-will-i-get error code of
    // 3 is expected for queries since it indicates that some of the arguments don't correspond to
    // valid targets. Note that this COULD result in legitimate issues being ignored, but it's
    // unlikely.
    val expectedExitCodes = if (allowPartialFailures) listOf(0, 3) else listOf(0)
    check(result.exitCode in expectedExitCodes) {
      "Expected non-zero exit code (not ${result.exitCode}) for command: ${result.command}." +
        "\nStandard output:\n${result.output.joinToString("\n")}" +
        "\nError output:\n${result.errorOutput.joinToString("\n")}"
    }
    return result.output
  }

  fun executeBazelRePinCommand(
    vararg arguments: String,
    allowPartialFailures: Boolean = false
  ): List<String> {
    val result =
      executeCommand(rootDirectory, command = "REPIN=1", *arguments, includeErrorOutput = false)
    // Per https://docs.bazel.build/versions/main/guide.html#what-exit-code-will-i-get error code of
    // 3 is expected for queries since it indicates that some of the arguments don't correspond to
    // valid targets. Note that this COULD result in legitimate issues being ignored, but it's
    // unlikely.
    val expectedExitCodes = if (allowPartialFailures) listOf(0, 3) else listOf(0)
    check(result.exitCode in expectedExitCodes) {
      "Expected non-zero exit code (not ${result.exitCode}) for command: ${result.command}." +
        "\nStandard output:\n${result.output.joinToString("\n")}" +
        "\nError output:\n${result.errorOutput.joinToString("\n")}"
    }
    return result.output
  }

  /**
   * Executes the specified [command] in the specified working directory [workingDir] with the
   * provided arguments being passed as arguments to the command.
   *
   * Any exceptions thrown when trying to execute the application will be thrown by this method.
   * Any failures in the underlying process should not result in an exception.
   *
   * @param includeErrorOutput whether to include error output in the returned [CommandResult],
   *     otherwise it's discarded
   * @return a [CommandResult] that includes the error code & application output
   */
  private fun executeCommand(
    workingDir: File,
    command: String,
    vararg arguments: String,
    includeErrorOutput: Boolean = true
  ): CommandResult {
    check(workingDir.isDirectory) {
      "Expected working directory to be an actual directory: $workingDir"
    }
    val assembledCommand = listOf(command) + arguments.toList()
    println(assembledCommand)
    val command = assembledCommand.joinToString(" ")
    println(command)
    val process = ProcessBuilder()
      .command("bash", "-c", command)
      .directory(workingDir)
      .redirectErrorStream(includeErrorOutput)
      .start()
    val finished = process.waitFor(WAIT_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    check(finished) { "Process did not finish within the expected timeout" }
    return CommandResult(
      process.exitValue(),
      process.inputStream.bufferedReader().readLines(),
      if (!includeErrorOutput) process.errorStream.bufferedReader().readLines() else listOf(),
      assembledCommand,
    )
  }
}

/** The result of executing a command using [executeCommand]. */
private data class CommandResult(
  /** The exit code of the application. */
  val exitCode: Int,
  /** The lines of output from the command, including both error & standard output lines. */
  val output: List<String>,
  /** The lines of error output, or empty if error output is redirected to [output]. */
  val errorOutput: List<String>,
  /** The fully-formed command line executed by the application to achieve this result. */
  val command: List<String>,
)