// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.learn.lesson.general

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.AboutPopup
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereUIBase
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.util.SystemInfo
import com.intellij.testGuiFramework.framework.GuiTestUtil
import com.intellij.testGuiFramework.impl.jList
import com.intellij.testGuiFramework.util.Key
import training.learn.LessonsBundle
import training.learn.interfaces.Module
import training.learn.lesson.kimpl.KLesson
import training.learn.lesson.kimpl.LessonContext
import training.learn.lesson.kimpl.LessonSample
import training.learn.lesson.kimpl.LessonUtil
import javax.swing.JPanel

class GotoActionLesson(module: Module, lang: String, private val sample: LessonSample) :
  KLesson("Actions", LessonsBundle.message("goto.action.lesson.name"), module, lang) {

  companion object {
    private const val FIND_ACTION_WORKAROUND: String = "https://intellij-support.jetbrains.com/hc/en-us/articles/360005137400-Cmd-Shift-A-hotkey-opens-Terminal-with-apropos-search-instead-of-the-Find-Action-dialog"
  }

  override val lessonContent: LessonContext.() -> Unit
    get() = {
      prepareSample(sample)
      actionTask("GotoAction") {
        val macOsWorkaround = if (SystemInfo.isMacOSMojave)
          LessonsBundle.message("goto.action.mac.workaround", LessonUtil.actionName(it), FIND_ACTION_WORKAROUND)
        else ""
        LessonsBundle.message("goto.action.use.find.action", LessonUtil.actionName(it), action(it)) + macOsWorkaround
      }
      actionTask("About") {
        LessonsBundle.message("goto.action.invoke.about.action",
                              strong(LessonsBundle.message("goto.action.about.word")), LessonUtil.rawEnter())
      }
      task {
        text(LessonsBundle.message("goto.action.to.return.to.the.editor", action("EditorEscape")))
        var aboutHasBeenFocused = false
        stateCheck {
          aboutHasBeenFocused = aboutHasBeenFocused || focusOwner is AboutPopup.PopupPanel
          aboutHasBeenFocused && focusOwner is EditorComponentImpl
        }
        test {
          ideFrame {
            waitComponent(JPanel::class.java, "InfoSurface")
            // Note 1: it is editor from test IDE fixture
            // Note 2: In order to pass this task without interference with later task I need to firstly focus lesson
            // and only then press Escape
            editor.requestFocus()
            GuiTestUtil.shortcut(Key.ESCAPE)
          }
        }
      }
      actionTask("GotoAction") {
        LessonsBundle.message("goto.action.invoke.again", code("Ran"), action(it))
      }
      val showLineNumbersName = IdeBundle.message("label.show.line.numbers")
      task(LessonsBundle.message("goto.action.show.line.input.required")) {
        text(LessonsBundle.message("goto.action.show.line.numbers.request", strong(it), strong(showLineNumbersName)))
        triggerByListItemAndHighlight { item ->
          item.toString().contains(showLineNumbersName)
        }
        test {
          waitComponent(SearchEverywhereUIBase::class.java)
          type(it)
        }
      }

      val lineNumbersShown = isLineNumbersShown()
      task {
        text(LessonsBundle.message("goto.action.first.lines.toggle", if (lineNumbersShown) 0 else 1))
        stateCheck { isLineNumbersShown() == !lineNumbersShown }
        restoreByUi()
        test {
          ideFrame {
            jList(showLineNumbersName).item(showLineNumbersName).click()
          }
        }
      }
      task {
        text(LessonsBundle.message("goto.action.second.lines.toggle", if (lineNumbersShown) 0 else 1))
        stateCheck { isLineNumbersShown() == lineNumbersShown }
        test {
          ideFrame {
            jList(showLineNumbersName).item(showLineNumbersName).click()
          }
        }
      }

      task {
        text(LessonsBundle.message("goto.action.propose.to.go.next", action("learn.next.lesson")))
      }
    }

  private fun isLineNumbersShown() = EditorSettingsExternalizable.getInstance().isLineNumbersShown
}