package com.taylor.spook.server

import android.app.Application

class SpookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Banco.iniciar(this)
        Registro.i("SPOOK SERVER ${Servidor.VERSAO} no ar (pid ${android.os.Process.myPid()})")
        Registro.i("banco: AndroidStore / SQLite WAL (fonte da verdade local)")
    }
}
