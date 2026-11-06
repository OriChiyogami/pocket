package io.pocket.host.core

class PackageException(message: String) : Exception(message)

class BridgeException(val code: String, message: String) : Exception(message)
