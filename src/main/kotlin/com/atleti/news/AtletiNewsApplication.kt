package com.atleti.news

import io.github.cdimascio.dotenv.Dotenv
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class AtletiNewsApplication

fun main(args: Array<String>) {
	val dotenv = Dotenv.configure().ignoreIfMissing().load()
	dotenv.entries().forEach { entry ->
		System.setProperty(entry.key, entry.value)
	}
	runApplication<AtletiNewsApplication>(*args)
}
