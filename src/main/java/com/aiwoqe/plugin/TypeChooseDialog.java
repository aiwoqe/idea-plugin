package com.aiwoqe.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * @author aiwoqe
 * @Type SampleDialogWrapper
 * @Desc
 * @date 2021年03月10日
 * @Version V1.0
 */
public abstract class TypeChooseDialog extends DialogWrapper {

    public TypeChooseDialog(Project project,String title) {
        // use current window as parent
        super(project,true);
        init();
        setTitle(title);

    }
}