plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("info.solidsoft.pitest") version "1.19.0"
}

group = "io.github.sitkowski01"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("com.tngtech.archunit:archunit:1.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	reports {
		html.required = true
		xml.required = true
	}
}

// Testy na testy: PIT psuje kod domeny (np. zamienia < na <=) i sprawdza,
// czy ktorys test to zauwazy. Mutant, ktory przezyl, to luka w testach.
pitest {
	junit5PluginVersion = "1.2.3"
	pitestVersion = "1.30.0"
	targetClasses = setOf("io.github.sitkowski01.exchange.domain.*", "io.github.sitkowski01.exchange.engine.*")
	targetTests = setOf("io.github.sitkowski01.exchange.domain.*", "io.github.sitkowski01.exchange.engine.*")
	threads = 4
	outputFormats = setOf("HTML", "XML")
	timestampedReports = false
	mutationThreshold = 85
	// Usuniecie logowania to mutant, ktorego zaden rozsadny test nie zabije.
	avoidCallsTo = setOf("java.lang.System\$Logger")
}
