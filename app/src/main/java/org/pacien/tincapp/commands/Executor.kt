/*
 * tinc app, an Android binding and user interface for the tinc mesh VPN daemon
 * Copyright (C) 2017-2018 Pacien TRAN-GIRARD
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.pacien.tincapp.commands

import android.os.AsyncTask
import java8.util.concurrent.CompletableFuture
import java8.util.function.Supplier
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * @author pacien
 */
internal object Executor {
  private const val FAILED = -1
  private const val SUCCESS = 0

  class CommandExecutionException(msg: String) : Exception(msg)

  init {
    System.loadLibrary("exec")
  }

  /**
   * @return FAILED (-1) on error, forked child PID otherwise
   */
  private external fun forkExec(argcv: Array<String>): Int

  /**
   * @return FAILED (-1) on error, the exit status of the process otherwise
   */
  private external fun wait(pid: Int): Int

  private fun read(stream: InputStream) = BufferedReader(InputStreamReader(stream)).readLines()

  fun forkExec(cmd: Command): CompletableFuture<Unit> {
    val pid = forkExec(cmd.asArray()).also {
      if (it == FAILED) throw CommandExecutionException("Could not fork child process.")
    }

    return runAsyncTask {
      when (wait(pid)) {
        SUCCESS -> Unit
        FAILED -> throw CommandExecutionException("Process terminated abnormally.")
        else -> throw CommandExecutionException("Non-zero exit status code.")
      }
    }
  }

  fun run(cmd: Command): Process = try {
    ProcessBuilder(cmd.asList()).start()
  } catch (e: IOException) {
    throw CommandExecutionException(e.message ?: "Could not start process.")
  }

  fun call(cmd: Command): CompletableFuture<List<String>> = run(cmd).let { process ->
    supplyAsyncTask<List<String>> {
      if (process.waitFor() == 0) read(process.inputStream)
      else throw CommandExecutionException(read(process.errorStream).lastOrNull() ?: "Non-zero exit status.")
    }
  }

  fun runAsyncTask(r: () -> Unit) = CompletableFuture.supplyAsync(Supplier(r), AsyncTask.THREAD_POOL_EXECUTOR)!!
  fun <U> supplyAsyncTask(s: () -> U) = CompletableFuture.supplyAsync(Supplier(s), AsyncTask.THREAD_POOL_EXECUTOR)!!
}
