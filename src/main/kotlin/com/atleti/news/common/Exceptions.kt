package com.atleti.news.common

class NotFoundException(message: String) : RuntimeException(message)

class InvalidRequestException(message: String) : RuntimeException(message)
