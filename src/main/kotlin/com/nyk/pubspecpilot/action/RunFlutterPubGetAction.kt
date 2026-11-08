package com.nyk.pubspecpilot.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.nyk.pubspecpilot.runner.FlutterPubGetRunner
import com.nyk.pubspecpilot.util.PubspecYamlUtils

class RunFlutterPubGetAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val isPubspec = PubspecYamlUtils.isPubspecFile(psiFile)
        e.presentation.isEnabledAndVisible = isPubspec
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        FlutterPubGetRunner.runPubGet(project, virtualFile, "Ejecución manual")
    }
}
