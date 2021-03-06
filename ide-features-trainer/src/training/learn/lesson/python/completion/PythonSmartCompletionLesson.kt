// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.learn.lesson.python.completion

import training.commands.kotlin.TaskContext
import training.learn.LessonsBundle
import training.learn.interfaces.Module
import training.learn.lesson.kimpl.KLesson
import training.learn.lesson.kimpl.LessonContext
import training.learn.lesson.kimpl.LessonUtil
import training.learn.lesson.kimpl.LessonUtil.checkExpectedStateOfEditor
import training.learn.lesson.kimpl.parseLessonSample

class PythonSmartCompletionLesson(module: Module)
  : KLesson("Smart completion", LessonsBundle.message("python.smart.completion.lesson.name"), module, "Python") {
  private val sample = parseLessonSample("""
    def f(x, file):
      x.append(file)
      x.rem<caret>
  """.trimIndent())

  override val lessonContent: LessonContext.() -> Unit
    get() {
      val methodName = "remove_duplicates"
      val insertedCode = "ove_duplicates()"
      return {
        prepareSample(sample)
        actionTask("CodeCompletion") {
          proposeRestoreMe()
          LessonsBundle.message("python.smart.completion.try.basic.completion", action(it))
        }
        task("SmartTypeCompletion") {
          text(LessonsBundle.message("python.smart.completion.use.smart.completion", code("x"), action(it)))
          triggerByListItemAndHighlight { ui ->
            ui.toString().contains(methodName)
          }
          proposeRestoreMe()
          test { actions(it) }
        }
        task {
          val result = LessonUtil.insertIntoSample(sample, insertedCode)
          text(LessonsBundle.message("python.smart.completion.finish.completion", code(methodName)))
          restoreByUi()
          stateCheck {
            editor.document.text == result
          }
          test {
            ideFrame {
              jListContains(methodName).item(methodName).doubleClick()
            }
          }
        }
      }
    }

  private fun TaskContext.proposeRestoreMe() {
    proposeRestore {
      checkExpectedStateOfEditor(sample)
    }
  }
}