package com.atleti.news

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class AtletiNewsApplication

fun main(args: Array<String>) {
	runApplication<AtletiNewsApplication>(*args)
}
