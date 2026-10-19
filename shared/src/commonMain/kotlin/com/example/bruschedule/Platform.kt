package com.example.bruschedule

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform