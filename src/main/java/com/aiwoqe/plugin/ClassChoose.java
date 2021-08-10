package com.aiwoqe.plugin;

import com.google.common.collect.Sets;
import com.intellij.ide.util.ClassFilter;
import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.HashSet;

/**
 * @author aiwoqe
 * @Type ClassChoose
 * @Desc
 * @date 2021年03月09日
 * @Version V1.0
 */
public class ClassChoose {
    public static PsiClass chooseClass(String title,Project project,Module module,GlobalSearchScope scope) {
        final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass initialSelection = facade.findClass("java.lang.Integer",scope);
        TreeClassChooser chooser = TreeClassChooserFactory.getInstance(project).createInheritanceClassChooser(title, scope, initialSelection, null,new ClassFilter() {
            @Override
            public boolean isAccepted(PsiClass aClass) {
                String qualifiedName = aClass.getQualifiedName();
                if (qualifiedName.contains("vo")||qualifiedName.contains("entity")||qualifiedName.contains("dto")) return true;
                return false;
            }
        });
        if (chooser == null) return null;
        chooser.showDialog();
        PsiClass selClass = chooser.getSelected();
        return selClass != null ? selClass : null;
    }
}
