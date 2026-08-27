package net.morsecode.ui

actual fun isDesktopPlatform(): Boolean = true

actual fun lanAddress(): String? = net.morsecode.net.NetworkInterfaces.primaryLanAddress()
