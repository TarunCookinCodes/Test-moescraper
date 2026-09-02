package com.example

class MoescrapperPlugin : Plugin() {
    override fun load(context: LoadContext) {
        registerMainAPI(MoescrapperProvider())
    }
}
