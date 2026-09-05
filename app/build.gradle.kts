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
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_21
	}
}

android {
	namespace = "io.github.melastore.shelf"
	compileSdk = 37

	defaultConfig {
		applicationId = "io.github.melastore.shelf"
		minSdk = 30
		targetSdk = 37
		versionName = "0.7.1"
		versionCode = 18
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
			// AGP otherwise writes the git HEAD at build time into
			// META-INF/version-control-info.textproto. That makes the APK depend on which commit
			// happened to be checked out, so F-Droid rebuilding from the tagged commit gets a
			// different byte and reproducible-build verification fails.
			vcsInfo { include = false }
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
		unitTests.all {
			it.maxHeapSize = "2048m"
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}

	sourceSets {
		named("main") {
			kotlin.directories.add("src/main/kotlin")
		}
		named("test") {
			kotlin.directories.add("src/test/kotlin")
		}
		// The Compose prompt tests need the activity that ui-test-manifest injects, which only the
		// debug variant has. Kept here rather than shipped into a release build to satisfy them.
		named("testDebug") {
			kotlin.directories.add("src/testDebug/kotlin")
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
	// The credential prompts are the one part of the UI a mistake locks the owner out of, so their
	// tests run with the rest rather than only on a device that happens to be plugged in.
	testDebugImplementation(platform(libs.androidx.compose.bom))
	testDebugImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.rules)
	androidTestImplementation(libs.androidx.test.runner)
}
