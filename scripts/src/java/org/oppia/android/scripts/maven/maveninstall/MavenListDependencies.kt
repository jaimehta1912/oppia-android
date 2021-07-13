package org.oppia.android.scripts.maven.maveninstall

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Data class that stores the list of dependencies present in `dependencies` array in
 * maven_install.json.
 */
@JsonClass(generateAdapter = true)
data class MavenListDependencies(
  @Json(name = "dependencies") val dependencyList: List<MavenListDependency>
)
