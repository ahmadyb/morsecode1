package net.morsecode.ui

actual fun isDesktopPlatform(): Boolean = false

actual fun lanAddress(): String? = net.morsecode.net.NetworkInterfaces.primaryLanAddress()
