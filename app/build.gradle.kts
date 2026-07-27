import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
	if (keystorePropertiesFile.isFile) {
		keystorePropertiesFile.inputStream().use(::load)
	}
}

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
	}
}

android {
	namespace = "io.github.melastore.shelf"
	compileSdk = 36

	defaultConfig {
		applicationId = "io.github.melastore.shelf"
		minSdk = 30
		targetSdk = 36
		versionCode = 2
		versionName = "0.2.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	if (keystorePropertiesFile.isFile) {
		signingConfigs {
			create("release") {
				storeFile = rootProject.file(keystoreProperties.required("storeFile"))
				storePassword = keystoreProperties.required("storePassword")
				keyAlias = keystoreProperties.required("keyAlias")
				keyPassword = keystoreProperties.required("keyPassword")
			}
		}
	}

	buildTypes {
		release {
			signingConfigs.findByName("release")?.let { signingConfig = it }
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	buildFeatures {
		compose = true
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	sourceSets {
		named("main") {
			kotlin.srcDir("src/main/kotlin")
		}
		named("test") {
			kotlin.srcDir("src/test/kotlin")
		}
	}

	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}
}

fun Properties.required(name: String): String = getProperty(name)?.takeIf { it.isNotBlank() }
	?: error("Missing $name in keystore.properties")

dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material.icons.core)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.documentfile)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.kotlinx.serialization.json)

	debugImplementation(libs.androidx.compose.ui.tooling)
	debugImplementation(libs.androidx.compose.ui.test.manifest)

	testImplementation(libs.junit)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.robolectric)
	testImplementation(libs.robolectric.android)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.rules)
	androidTestImplementation(libs.androidx.test.runner)
}
