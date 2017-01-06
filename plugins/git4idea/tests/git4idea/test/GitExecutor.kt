/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("GitExecutor")

package git4idea.test

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.Executor
import com.intellij.testFramework.vcs.ExecutableHelper
import git4idea.repo.GitRepository
import org.junit.Assert.assertFalse
import java.io.File

private val LOG: Logger = logger("#git4idea.test.GitExecutor")
private val MAX_RETRIES = 3
private var myVersionPrinted = false

fun gitExecutable() = GitExecutorHolder.PathHolder.GIT_EXECUTABLE

@JvmOverloads fun git(command: String, ignoreNonZeroExitCode: Boolean = false): String {
  printVersionTheFirstTime()
  return doCallGit(command, ignoreNonZeroExitCode)
}

private fun doCallGit(command: String, ignoreNonZeroExitCode: Boolean): String {
  val split = Executor.splitCommandInParameters(command)
  split.add(0, gitExecutable())
  val workingDir = Executor.ourCurrentDir()
  Executor.debug("[" + workingDir.name + "] # git " + command)
  for (attempt in 0..MAX_RETRIES - 1) {
    var stdout: String
    try {
      stdout = Executor.run(workingDir, split, ignoreNonZeroExitCode)
      if (!isIndexLockFileError(stdout)) {
        return stdout
      }
    }
    catch (e: Executor.ExecutionException) {
      stdout = e.output
      if (!isIndexLockFileError(stdout)) {
        throw e
      }
    }

    LOG.info("Index lock file error, attempt #$attempt: $stdout")
  }
  throw RuntimeException("fatal error during execution of Git command: \$command")
}

private fun isIndexLockFileError(stdout: String): Boolean {
  return stdout.contains("fatal") && stdout.contains("Unable to create") && stdout.contains(".git/index.lock")
}

fun git(repository: GitRepository?, command: String): String {
  if (repository != null) {
    cd(repository)
  }
  return git(command)
}

fun git(formatString: String, vararg args: String): String {
  return git(String.format(formatString, *args))
}

fun cd(repository: GitRepository) {
  Executor.cd(repository.root.path)
}

@JvmOverloads fun add(path: String = ".") {
  git("add --verbose " + path)
}

fun addCommit(message: String): String {
  add()
  return commit(message)
}

fun checkout(vararg params: String) {
  git("checkout " + StringUtil.join(params, " "))
}

fun commit(message: String): String {
  git("commit -m '$message'")
  return last()
}

fun tac(file: String): String {
  Executor.touch(file, "content" + Math.random())
  return addCommit("touched " + file)
}

fun modify(file: String): String {
  Executor.overwrite(file, "content" + Math.random())
  return addCommit("modified " + file)
}

fun last(): String {
  return git("log -1 --pretty=%H")
}

fun log(vararg params: String): String {
  return git("log " + StringUtil.join(params, " "))
}

fun mv(fromPath: String, toPath: String) {
  git("mv $fromPath $toPath")
}

fun mv(from: File, to: File) {
  mv(from.path, to.path)
}

private fun printVersionTheFirstTime() {
  if (!myVersionPrinted) {
    myVersionPrinted = true
    doCallGit("version", false)
  }
}

fun file(fileName: String): TestFile {
  val f = Executor.child(fileName)
  return TestFile(f)
}

private class GitExecutorHolder {
  //using inner class to avoid extra work during class loading of unrelated tests
  internal object PathHolder {
    internal val GIT_EXECUTABLE = ExecutableHelper.findGitExecutable()!!
  }
}

class TestFile internal constructor(private val myFile: File) {

  fun append(content: String): TestFile {
    FileUtil.writeToFile(myFile, content.toByteArray(), true)
    return this
  }

  fun write(content: String): TestFile {
    FileUtil.writeToFile(myFile, content.toByteArray(), false)
    return this
  }

  fun create(content: String): TestFile {
    assertNotExists()
    FileUtil.writeToFile(myFile, content.toByteArray(), false)
    return this
  }

  fun assertNotExists(): TestFile {
    assertFalse(myFile.exists())
    return this
  }

  fun add(): TestFile {
    add(myFile.path)
    return this
  }

  fun exists(): Boolean {
    return myFile.exists()
  }
}
