package org.autojs.build

import org.gradle.api.Project
import java.io.File
import java.util.*

class Signs @JvmOverloads constructor(project: Project, filePath: String = "${project.rootDir}/sign.properties") {

    var isValid = false
        private set

    val properties = Properties().also { props ->
        val localFile = File(filePath)
        if (localFile.exists()) {
            localFile.inputStream().use { props.load(it) }
            isValid = props.isNotEmpty()
        } else {
            val environment = System.getenv()
            val names = mapOf(
                "storeFile" to "AUTOJS_SIGNING_STORE_FILE",
                "storePassword" to "AUTOJS_SIGNING_STORE_PASSWORD",
                "keyAlias" to "AUTOJS_SIGNING_KEY_ALIAS",
                "keyPassword" to "AUTOJS_SIGNING_KEY_PASSWORD",
            )
            if (names.values.all { !environment[it].isNullOrEmpty() }) {
                names.forEach { (property, variable) -> props[property] = environment.getValue(variable) }
                isValid = true
            }
        }
    }

}
