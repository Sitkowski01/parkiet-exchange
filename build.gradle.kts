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
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-kafka")
	testImplementation("org.awaitility:awaitility")
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
	targetClasses = setOf("io.github.sitkowski01.exchange.domain.*", "io.github.sitkowski01.exchange.engine.*", "io.github.sitkowski01.exchange.api.*",
		"io.github.sitkowski01.exchange.marketdata.*", "io.github.sitkowski01.exchange.bots.*")
	targetTests = setOf("io.github.sitkowski01.exchange.domain.*", "io.github.sitkowski01.exchange.engine.*", "io.github.sitkowski01.exchange.api.*",
		"io.github.sitkowski01.exchange.marketdata.*", "io.github.sitkowski01.exchange.bots.*")
	// Klasy *Config tylko skladaja beany -- sprawdza je test calej aplikacji, nie PIT.
	excludedClasses = setOf("*Config", "*Config$*")
	threads = 4
	outputFormats = setOf("HTML", "XML")
	timestampedReports = false
	mutationThreshold = 85
	// Usuniecie logowania to mutant, ktorego zaden rozsadny test nie zabije.
	avoidCallsTo = setOf("java.lang.System\$Logger")
}

// Benchmarki JMH w osobnym zestawie zrodel: nie trafiaja do jara ani do testow.
// ./gradlew jmh            -- wszystkie (kilka minut)
// ./gradlew jmh -Pquick    -- szybki przebieg do sprawdzenia, ze dzialaja
val jmh: SourceSet by sourceSets.creating {
	compileClasspath += sourceSets.main.get().output
	runtimeClasspath += sourceSets.main.get().output
}
configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
	"jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
	"jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
	group = "verification"
	description = "Runs JMH benchmarks"
	classpath = jmh.runtimeClasspath
	mainClass = "org.openjdk.jmh.Main"
	val quick = project.hasProperty("quick")
	args(if (quick) listOf("-f", "1", "-wi", "1", "-i", "2", "-w", "1s", "-r", "1s") else listOf("-f", "2", "-wi", "3", "-i", "5"))
	args("-rf", "json", "-rff", layout.buildDirectory.file("reports/jmh/results.json").get().asFile.path)
	doFirst { layout.buildDirectory.dir("reports/jmh").get().asFile.mkdirs() }
}
