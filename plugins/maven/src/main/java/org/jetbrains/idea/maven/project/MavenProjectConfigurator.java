package org.jetbrains.idea.maven.project;

import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Function;
import org.jetbrains.idea.maven.state.MavenProjectsManager;

import java.io.IOException;
import java.util.*;

public class MavenProjectConfigurator {
  private Project myProject;
  private ModifiableModuleModel myModuleModel;
  private MavenProjectModelManager myProjectModel;
  private MavenImporterSettings mySettings;
  private List<ModifiableRootModel> myRootModelsToCommit = new ArrayList<ModifiableRootModel>();

  public static void config(Project p,
                            MavenProjectModelManager projectModel,
                            MavenImporterSettings settings) {
    MavenProjectConfigurator c = new MavenProjectConfigurator(p, projectModel, settings);
    c.config();
  }

  private MavenProjectConfigurator(Project p,
                              MavenProjectModelManager projectModel,
                              MavenImporterSettings settings) {
    myProject = p;
    myProjectModel = projectModel;
    mySettings = settings;
  }

  private void config() {
    myModuleModel = ModuleManager.getInstance(myProject).getModifiableModel();
    configSettings();
    deleteObsoleteModules();
    configModules();
    configModuleGroups();
    commit();
  }

  private void configSettings() {
    ((ProjectEx)myProject).setSavePathsRelative(true);
  }


  private void deleteObsoleteModules() {
    List<Module> obsolete = collectObsoleteModules();
    if (obsolete.isEmpty()) return;

    MavenProjectsManager.getInstance(myProject).setRegularModules(obsolete);

    String formatted = StringUtil.join(obsolete, new Function<Module, String>() {
      public String fun(Module m) {
        return "'" + m.getName() + "'";
      }
    }, "\n");

    int result = Messages.showYesNoDialog(myProject,
                                          ProjectBundle.message("maven.import.message.delete.obsolete", formatted),
                                          ProjectBundle.message("maven.import"),
                                          Messages.getQuestionIcon());
    if (result == 1) return;// NO

    for (Module each : obsolete) {
      myModuleModel.disposeModule(each);
    }
  }

  private List<Module> collectObsoleteModules() {
    final List<Module> linkedModules = new ArrayList<Module>();
    myProjectModel.visit(new MavenProjectModelManager.SimpleVisitor() {
      public void visit(MavenProjectModel node) {
        Module m = node.getIdeaModule();
        if (m != null) linkedModules.add(m);
      }
    });

    List<Module> remainingModules = new ArrayList<Module>();
    Collections.addAll(remainingModules, ModuleManager.getInstance(myProject).getModules());
    remainingModules.removeAll(linkedModules);

    List<Module> obsolete = new ArrayList<Module>();
    for (Module each : remainingModules) {
      if (MavenProjectsManager.getInstance(myProject).isMavenizedModule(each)) {
        obsolete.add(each);
      }
    }
    return obsolete;
  }

  private void configModules() {
    // we must preserve the natural order.
    final LinkedHashMap<MavenProjectModel, Module> modules = new LinkedHashMap<MavenProjectModel, Module>();

    myProjectModel.visit(new MavenProjectModelManager.SimpleVisitor() {
      public void visit(MavenProjectModel each) {

        Module m = createModule(each);
        modules.put(each, m);
      }
    });

    LinkedHashMap<Module, ModifiableRootModel> rootModels = new LinkedHashMap<Module, ModifiableRootModel>();
    for (Map.Entry<MavenProjectModel, Module> each : modules.entrySet()) {
      if (each.getKey().isAggregator()) continue;

      ModifiableRootModel model = createModuleConfigurator(each.getValue(), each.getKey()).config();
      rootModels.put(each.getValue(), model);
    }


    for (Map.Entry<MavenProjectModel, Module> each : modules.entrySet()) {
      if (each.getKey().isAggregator()) continue;
      createModuleConfigurator(each.getValue(), each.getKey()).preConfigFacets(rootModels.get(each.getValue()));
    }

    for (Map.Entry<MavenProjectModel, Module> each : modules.entrySet()) {
      if (each.getKey().isAggregator()) continue;
      createModuleConfigurator(each.getValue(), each.getKey()).configFacets(rootModels.get(each.getValue()));
    }

    myRootModelsToCommit.addAll(rootModels.values());

    MavenProjectsManager.getInstance(myProject).setMavenizedModules(new ArrayList<Module>(modules.values()));
  }

  private Module createModule(MavenProjectModel node) {
    Module module = node.getIdeaModule();
    if (module == null) {
      String path = node.getModuleFilePath();
      // for some reason newModule opens the existing iml file, so we
      // have to remove it beforehand.
      removeExistingIml(path);
      module = myModuleModel.newModule(path, StdModuleTypes.JAVA);
      node.setIdeaModule(module);
    }
    return module;
  }

  private MavenModuleConfigurator createModuleConfigurator(Module module, MavenProjectModel mavenProject) {
    return new MavenModuleConfigurator(myModuleModel, myProjectModel, mySettings, module, mavenProject);
  }

  private void removeExistingIml(String path) {
    VirtualFile existingFile = LocalFileSystem.getInstance().findFileByPath(path);
    if (existingFile == null) return;
    try {
      existingFile.delete(this);
    }
    catch (IOException ignore) {
    }
  }

  private void configModuleGroups() {
    if (!mySettings.isCreateModuleGroups()) return;

    final Stack<String> groups = new Stack<String>();

    final boolean createTopLevelGroup = myProjectModel.getRootProjects().size() > 1;

    myProjectModel.visit(new MavenProjectModelManager.SimpleVisitor() {
      int depth = 0;

      public void visit(MavenProjectModel node) {
        depth++;

        String name = node.getModuleName();

        if (shouldCreateGroup(node)) {
          groups.push(ProjectBundle.message("module.group.name", name));
        }

        Module module = myModuleModel.findModuleByName(name);
        myModuleModel.setModuleGroupPath(module, groups.isEmpty() ? null : groups.toArray(new String[groups.size()]));
      }

      public void leave(MavenProjectModel node) {
        if (shouldCreateGroup(node)) {
          groups.pop();
        }
        depth--;
      }

      private boolean shouldCreateGroup(MavenProjectModel node) {
        return !node.getModules().isEmpty() && (createTopLevelGroup || depth > 1);
      }
    });
  }

  private void commit() {
    ModifiableRootModel[] rootModels = myRootModelsToCommit.toArray(new ModifiableRootModel[myRootModelsToCommit.size()]);
    ProjectRootManager.getInstance(myProject).multiCommit(myModuleModel, rootModels);
  }
}
