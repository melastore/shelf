import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
		versionCode = 1
		versionName = "0.1.0"
	}

	buildTypes {
		release {
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

	testImplementation(libs.junit)
}
