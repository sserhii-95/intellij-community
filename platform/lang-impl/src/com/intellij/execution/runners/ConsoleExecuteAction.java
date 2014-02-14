/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.execution.runners;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.execution.console.LanguageConsoleImpl;
import com.intellij.execution.console.LanguageConsoleView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.EmptyAction;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import org.jetbrains.annotations.NotNull;

public class ConsoleExecuteAction extends DumbAwareAction {
  static final String CONSOLE_EXECUTE_ACTION_ID = "Console.Execute";

  private final LanguageConsoleView myConsole;
  private final BaseConsoleExecuteActionHandler myExecuteActionHandler;
  private final Condition<LanguageConsoleImpl> myEnabledCondition;

  public ConsoleExecuteAction(@NotNull LanguageConsoleView console, @NotNull BaseConsoleExecuteActionHandler executeActionHandler) {
    this(console, executeActionHandler, CONSOLE_EXECUTE_ACTION_ID, Conditions.<LanguageConsoleImpl>alwaysTrue());
  }

  public ConsoleExecuteAction(@NotNull LanguageConsoleView console, @NotNull BaseConsoleExecuteActionHandler executeActionHandler,
                              @NotNull String emptyExecuteActionId, @NotNull Condition<LanguageConsoleImpl> enabledCondition) {
    super(null, null, AllIcons.Actions.Execute);

    myConsole = console;
    myExecuteActionHandler = executeActionHandler;
    myEnabledCondition = enabledCondition;

    EmptyAction.setupAction(this, emptyExecuteActionId, null);
  }

  @Override
  public final void update(AnActionEvent e) {
    EditorEx editor = myConsole.getConsole().getConsoleEditor();
    Lookup lookup = LookupManager.getActiveLookup(editor);
    e.getPresentation().setEnabled(!editor.isRendererMode() && isEnabled() &&
                                   (lookup == null || !lookup.isCompletion()));
  }

  @Override
  public final void actionPerformed(AnActionEvent e) {
    myExecuteActionHandler.runExecuteAction(myConsole);
  }

  protected boolean isEnabled() {
    return myEnabledCondition.value(myConsole.getConsole());
  }
}