// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.ui.views

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.guessCurrentProject
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.FeaturesTrainerIcons
import training.learn.CourseManager
import training.learn.LearnBundle
import training.learn.interfaces.Lesson
import training.learn.interfaces.Module
import training.ui.UISettings
import training.util.createBalloon
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

class LearningItems : JPanel() {
  var modules: List<Module> = emptyList()
  private val expanded: MutableSet<Module> = mutableSetOf()

  init {
    name = "learningItems"
    layout = VerticalLayout(10)
    border = UISettings.instance.eastBorder
    isOpaque = false
    isFocusable = false
  }

  fun updateItems() {
    layout = VerticalLayout(10)
    removeAll()
    for (module in modules) {
      if (module.lessons.isEmpty()) continue
      add(createModuleItem(module))
      if (expanded.contains(module)) {
        for (lesson in module.lessons) {
          add(createLessonItem(lesson))
        }
      }
    }
    revalidate()
    repaint()
  }

  private fun createLessonItem(lesson: Lesson): JPanel {
    val result = JPanel()
    result.layout = HorizontalLayout(5)
    val checkmarkIconLabel = JLabel(if (lesson.passed) FeaturesTrainerIcons.GreenCheckmark else EmptyIcon.ICON_16)
    result.add(JLabel(EmptyIcon.ICON_16))
    result.add(checkmarkIconLabel)

    val name = LinkLabel<Any>(lesson.name, null)
    name.setListener(
      { _, _ ->
        val project = guessCurrentProject(this)
        val dumbService = DumbService.getInstance(project)
        if (dumbService.isDumb) {
          val balloon = createBalloon(LearnBundle.message("indexing.message"))
          balloon.showInCenterOf(name)
          return@setListener
        }
        CourseManager.instance.openLesson(project, lesson)
      }, null)
    //name.font = JBFont.label().asPlain().deriveFont(16.0f)
    result.add(name)
    return result
  }

  private fun createModuleItem(module: Module): JPanel {
    val result = JPanel()

    result.toolTipText = module.description

    result.layout = HorizontalLayout(5)

    result.border = JBUI.Borders.empty(5, 7)
    val expandIcon = IconUtil.toSize(if (expanded.contains(module)) UIUtil.getTreeExpandedIcon() else UIUtil.getTreeCollapsedIcon(),
                                     JBUIScale.scale(16), JBUIScale.scale(16))
    val expandIconLabel = JLabel(expandIcon)

    val name = JLabel(module.name)

    result.add(expandIconLabel)
    result.add(name)

    if (!module.hasNotPassedLesson()) {
      val checkMarkIcon = if (!module.hasNotPassedLesson()) FeaturesTrainerIcons.Checkmark else EmptyIcon.ICON_16
      val checkmarkIconLabel = JLabel(checkMarkIcon)

      checkmarkIconLabel.border = JBUI.Borders.emptyRight(8)
      checkmarkIconLabel.verticalAlignment = SwingConstants.CENTER
      result.add(checkmarkIconLabel)
    }
    else {
      createModuleProgressLabel(module)?.let {
        result.add(UISettings.rigidGap(UISettings::progressGap, isVertical = false))
        result.add(it)
      }
    }

    result.addMouseListener(object : MouseListener {
      override fun mouseClicked(e: MouseEvent) {
        if (expanded.contains(module)) {
          expanded.remove(module)
        }
        else {
          expanded.clear()
          expanded.add(module)
        }
        updateItems()
      }

      override fun mousePressed(e: MouseEvent) {
      }

      override fun mouseReleased(e: MouseEvent) {
      }

      override fun mouseEntered(e: MouseEvent) {
        result.background = Color(0, 0, 0, 50)
        result.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        result.revalidate()
        result.repaint()
      }

      override fun mouseExited(e: MouseEvent) {
        result.background = null
        result.cursor = Cursor.getDefaultCursor()
        result.revalidate()
        result.repaint()
      }
    })
    return result
  }

  private fun createModuleProgressLabel(module: Module): JBLabel? {
    val progressStr = module.calcProgress() ?: return null
    val progressLabel = JBLabel(progressStr)
    progressLabel.border = EmptyBorder(0, 5, 0, 5)
    progressLabel.name = "progressLabel"
    progressLabel.font = UISettings.instance.italicFont
    progressLabel.foreground = UISettings.instance.passedColor
    progressLabel.alignmentY = Component.BOTTOM_ALIGNMENT
    return progressLabel
  }
}
