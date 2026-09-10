package dev.wasdevv.tally

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TallyApplication

fun main(args: Array<String>) {
    runApplication<TallyApplication>(*args)
}
