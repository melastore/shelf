plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.kotlin.android) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.spotless)
}

spotless {
	kotlin {
		target("app/src/**/*.kt")
		ktlint("1.8.0")
	}
	kotlinGradle {
		target("*.gradle.kts", "app/*.gradle.kts")
		ktlint("1.8.0")
	}
}
