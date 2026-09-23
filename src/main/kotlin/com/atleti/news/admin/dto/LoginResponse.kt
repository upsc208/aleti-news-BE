package com.atleti.news.admin.dto

data class LoginResponse(val token: String, val expiresInSeconds: Long)
