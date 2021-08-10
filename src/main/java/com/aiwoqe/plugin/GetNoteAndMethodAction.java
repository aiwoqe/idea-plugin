package com.aiwoqe.plugin;


import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlComment;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.util.IncorrectOperationException;
import org.apache.http.util.TextUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author aiwoqe
 * @Type GetNoteAndMethodAction
 * @Desc
 * @date 2021年03月08日
 * @Version V1.0
 */
public class GetNoteAndMethodAction extends AnAction {

    private boolean stopped=false;

    @Override
    public void actionPerformed(AnActionEvent e) {
        final Editor editor = e.getData(PlatformDataKeys.EDITOR);
        if (null == editor) {
            return;
        }
        SelectionModel model = editor.getSelectionModel();
        stopped=false;
        //获取service方法名
        final String serviceMethodName = model.getSelectedText();
        if (TextUtils.isEmpty(serviceMethodName)) {
            //消息提示
            showPopupBalloon(editor, "未选中内容");
            return;
        }
        //参数所在的偏移
        String lineText = getLineText(model, editor);
        //service名的前缀
        String serviceLowerNamePrefix = parseServiceName(lineText);
        //service名的前缀首字母大写
        String serviceUpperNamePrefix = serviceLowerNamePrefix.substring(0, 1).toUpperCase() + serviceLowerNamePrefix.substring(1);
        //解析方法参数列表
        String[] argNameArr = parseArg(lineText, serviceMethodName);



        //项目参数
        Project project = e.getProject();
        Module module = e.getData(LangDataKeys.MODULE);
        GlobalSearchScope scope = GlobalSearchScope.moduleScope(module);

        //import列表
        HashSet<String> importSet = new HashSet<>();

        //确定每个参数的类型
        String[] argTypeArr = dialogArgType(argNameArr, project, module, scope, importSet);

        if (stopped)
            return;
        //确定返回值的类型
        String returnType = dialogReturnType("返回值类型选择", "请为方法名" + serviceMethodName + "选择返回值类型", project, module, scope, importSet);

        if (stopped)
            return;
        //确定service,serviceimpl,dao,mapper的注释
        String simpleComment = dialogMethodComment("方法注释输入", "请为方法" + serviceMethodName + "写入注释", project);

        //对话框关闭，中止
        if (simpleComment==null)
            return;
        //注释拼接
        String comment = "/**\n" +
                "     * " + simpleComment + "\n" +
                "     *\n";
        for (int i = 0; i < argNameArr.length; i++) {
            comment += "     * @param " + argNameArr[i] + "\n";
        }
        comment += "     * @return\n" +
                "     */";

        String returnValueSimpleName = "";
        if (NO_RETURN_VALUE.equals(returnType))
            returnValueSimpleName = NO_RETURN_VALUE;
        else
            returnValueSimpleName = qualifiedNameToSimple(returnType);

        //service方法生成
        PsiClass[] servicePsiClass = PsiShortNamesCache.getInstance(project).getClassesByName(serviceUpperNamePrefix + SERVICE_NAME, scope);
        String serviceMethod = returnValueSimpleName + " " + serviceMethodName + "(";
        for (int i = 0; i < argNameArr.length; i++) {
            serviceMethod += qualifiedNameToSimple(argTypeArr[i]) + " " + argNameArr[i] + ",";
        }
        if (argNameArr.length!=0)
            serviceMethod = serviceMethod.substring(0, serviceMethod.length() - 1);
        serviceMethod += ");";
        writeMethod(project, serviceMethod, comment, servicePsiClass[0], importSet, scope);

        //serviceImpl方法代码生成
        PsiClass[] serviceImplPsiClass = PsiShortNamesCache.getInstance(project).getClassesByName(serviceUpperNamePrefix + SERVICE_IMPL_NAME, scope);
        String serviceImplMethod = "@Override\npublic " + returnValueSimpleName + " " + serviceMethodName + "(";
        for (int i = 0; i < argNameArr.length; i++) {
            serviceImplMethod += qualifiedNameToSimple(argTypeArr[i]) + " " + argNameArr[i] + ",";
        }
        if (argNameArr.length!=0)
            serviceImplMethod = serviceImplMethod.substring(0, serviceImplMethod.length() - 1);
        serviceImplMethod += "){\n";
        if (!NO_RETURN_VALUE.equals(returnValueSimpleName)) {
            serviceImplMethod += "return ";
        }
        serviceImplMethod += serviceLowerNamePrefix + DAO_NAME + "." + serviceMethodName + "(";
        for (int i = 0; i < argNameArr.length; i++) {
            serviceImplMethod += argNameArr[i] + ",";
        }
        if (argNameArr.length!=0)
            serviceImplMethod = serviceImplMethod.substring(0, serviceImplMethod.length() - 1);
        serviceImplMethod += ");\n}";
        writeMethod(project, serviceImplMethod, comment, serviceImplPsiClass[0], importSet, scope);

        //dao方法代码生成
        PsiClass[] daoPsiClass = PsiShortNamesCache.getInstance(project).getClassesByName(serviceUpperNamePrefix + DAO_NAME, scope);
        importSet.add("org.apache.ibatis.annotations.Param");
        String daoMethod = returnValueSimpleName + " " + serviceMethodName + "(";
        for (int i = 0; i < argNameArr.length; i++) {
            daoMethod += "@Param(\"" + argNameArr[i] + "\")" + qualifiedNameToSimple(argTypeArr[i]) + " " + argNameArr[i] + ",";
        }
        if (argNameArr.length!=0)
            daoMethod = daoMethod.substring(0, daoMethod.length() - 1);
        daoMethod += ");";
        writeMethod(project, daoMethod, comment, daoPsiClass[0], importSet, scope);


        XmlFile xmlFile = findMybatisMapperXmlFile(project, scope, serviceUpperNamePrefix + DAO_NAME);
        XmlTag rootTag = xmlFile.getRootTag();
        String mapperTypeChoose = mapperTypeChoose("mapper.xml方法选择", null, serviceMethodName);
        WriteCommandAction.runWriteCommandAction(project, () -> {
            XmlTag tag = XmlElementFactory.getInstance(project).createTagFromText(mapperTypeChoose);
            @NotNull PsiElement[] children = rootTag.getChildren();
            PsiElement addTag = rootTag.addAfter(tag, children[5]);
            XmlComment xmlComment = createComment(project, "<foo><!--" + simpleComment + "--></foo>");
            XmlText xmlText1 = createXmlText(project, "<foo>\n</foo>");
            XmlText xmlText2 = createXmlText(project, "<foo>\n\n</foo>");
            rootTag.addBefore(xmlComment, addTag);
            rootTag.addBefore(xmlText1, addTag);
            rootTag.addAfter(xmlText2, addTag);
            jumpToXml(project, xmlFile, addTag);
        });
    }

    private XmlComment createComment(Project project, String s) throws IncorrectOperationException {
        final XmlTag element = XmlElementFactory.getInstance(project).createTagFromText(s, XMLLanguage.INSTANCE);
        final XmlComment newComment = PsiTreeUtil.getChildOfType(element, XmlComment.class);
        assert newComment != null;
        return newComment;
    }

    private XmlText createXmlText(Project project, String s) throws IncorrectOperationException {
        final XmlTag element = XmlElementFactory.getInstance(project).createTagFromText(s, XMLLanguage.INSTANCE);
        XmlText xmlText = PsiTreeUtil.getChildOfType(element, XmlText.class);
        assert xmlText != null;
        return xmlText;
    }

    private static final String[] mapperTypeList = new String[]{"select", "update", "insert", "delete"};

    private String selectTemplate(String methodName) {
        return "\n<select id=\"" + methodName + "\" resultMap=\"\">\n\n" +
                "    </select>";
    }

    private String updateTemplate(String methodName) {
        return "\n<update id=\"" + methodName + "\">\n\n" +
                "    </update>";
    }

    private String insertTemplate(String methodName) {
        return "\n<insert id=\"" + methodName + "\" keyProperty=\"\" useGeneratedKeys=\"true\">\n\n" +
                "    </insert>";
    }

    private String deleteTemplate(String methodName) {
        return "\n<delete id=\"" + methodName + "\">\n\n" +
                "    </delete>";
    }

    private String mapperTypeChoose(String title, String message, String methodName) {
        int mapperTypeIndex = messageShow(title, message, mapperTypeList);
        String res = null;
        switch (mapperTypeIndex) {
            case 0:
                res = selectTemplate(methodName);
                break;
            case 1:
                res = updateTemplate(methodName);
                break;
            case 2:
                res = insertTemplate(methodName);
                break;
            case 3:
                res = deleteTemplate(methodName);
                break;
            default:
                break;
        }
        return res;
    }

    private String dialogMethodComment(String title, String message, Project project) {
        return Messages.showInputDialog(project, message, title, null);
    }

    private static final String DUMMY_FILE_NAME = "_Dummy_." + JavaFileType.INSTANCE.getDefaultExtension();

    private void writeMethod(Project project, String method, String comment, PsiElement element, HashSet<String> importSet, GlobalSearchScope scope) {
        WriteCommandAction.runWriteCommandAction(project, () -> {

            String methodWithComment = comment.trim() + method;
            // 使用PsiElementFactory创建表达式元素
            PsiMethod newElement = PsiElementFactory.getInstance(project)
                    .createMethodFromText(methodWithComment, element.getContext());
            // 将新创建的表达式元素插入到光标停留在的元素的后面
            element.add(newElement);
            for (String importName : importSet
            ) {
                PsiClass aClass = JavaPsiFacade.getInstance(project).findClass(importName, scope);
                if (element instanceof PsiClass) {
                    PsiImportStatement importStatement;
                    PsiClass psiClass = (PsiClass) element;
                    PsiJavaFile file = (PsiJavaFile) psiClass.getParent();
                    PsiImportList importList = file.getImportList();
                    if (aClass == null) {
                        final PsiJavaFile aFile = createDummyJavaFile(project, "import " + importName + ";");
                        final PsiImportStatementBase statement = extractImport(aFile, false);
                        importStatement = (PsiImportStatement) CodeStyleManager.getInstance(project).reformat(statement);
                        importList.add(importStatement);
                    } else {
                        importStatement = PsiElementFactory.getInstance(project)
                                .createImportStatement(aClass);
                        importList.add(importStatement);
                    }
                }
            }
            CodeStyleManager.getInstance(project).reformat(element);
            JavaCodeStyleManager.getInstance(project).optimizeImports(element.getContainingFile());
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(element);
        });
    }

    private PsiJavaFile createDummyJavaFile(Project project, String text) {
        return (PsiJavaFile) PsiFileFactory.getInstance(project).createFileFromText(DUMMY_FILE_NAME, JavaFileType.INSTANCE, text);
    }

    private static PsiImportStatementBase extractImport(final PsiJavaFile aFile, final boolean isStatic) {
        final PsiImportList importList = aFile.getImportList();
        assert importList != null : aFile;
        final PsiImportStatementBase[] statements = isStatic ? importList.getImportStaticStatements() : importList.getImportStatements();
        assert statements.length == 1 : aFile.getText();
        return statements[0];
    }

    private static final String SERVICE_NAME = "Service";
    private static final String SERVICE_IMPL_NAME = "ServiceImpl";
    private static final String DAO_NAME = "Dao";
    private static final String MAPPER_NAME = "Mapper";

    private static final String[] ARG_LIST =
            new String[]{"java.lang.Integer", "java.lang.String", "java.lang.Long", "java.lang.Double", "自定义类"};
    private static final int ARG_DIY_INDEX = ARG_LIST.length - 1;
    private static final String[] RETURN_LIST =
            new String[]{"void", "List", "自定义","Integer", "String"};
    private static final int RETURN_DIY_INDEX = 2;
    private static final int RETURN_LIST_INDEX = 1;
    private static final String NO_RETURN_VALUE = "void";

    /**
     * 对话框确定返回值类型
     * @param title
     * @param message
     * @param project
     * @param module
     * @param scope
     * @param importSet
     * @return
     */
    private String dialogReturnType(String title, String message, Project project, Module module, GlobalSearchScope scope, HashSet<String> importSet) {
        int typeIndex = messageShow(title, message, RETURN_LIST);
        if (RETURN_DIY_INDEX == typeIndex) {
            PsiClass psiClass = ClassChoose.chooseClass(title, project, module, scope);
            if (psiClass==null){
                stopped=true;
                return "";
            }
            String qualifiedName = psiClass.getQualifiedName();
            importSet.add(qualifiedName);
            return qualifiedName;
        } else if (RETURN_LIST_INDEX == typeIndex) {
            importSet.add("java.util.List");
            int genericIndex = messageShow("请选择返回值List的泛型类型", message, ARG_LIST);
            if (ARG_DIY_INDEX == genericIndex) {
                PsiClass psiClass = ClassChoose.chooseClass("请选择返回值List的泛型类型", project, module, scope);
                if (psiClass==null){
                    stopped=true;
                    return "";
                }
                String qualifiedName = psiClass.getQualifiedName();
                importSet.add(qualifiedName);
                return "List<" + qualifiedNameToSimple(qualifiedName) + ">";
            } else
                return "List<" + qualifiedNameToSimple(ARG_LIST[genericIndex]) + ">";
        } else if (typeIndex == -1) {
            stopped=true;
        }else {
            return RETURN_LIST[typeIndex];
        }
        return NO_RETURN_VALUE;
    }

    /**
     * 跳转到目标文件
     */
    private void jumpToXml(Project project, XmlFile xmlFile, PsiElement tag) {
        if (xmlFile == null) return;
        // 关键词:偏移量(匹配"keyword"位置)
        int offset = 0;
//        XmlTag tag=
        if (tag != null) {
            offset = tag.getTextOffset() + 1;
        }
        // 打开文件, 并跳转到指定位置
        OpenFileDescriptor d = new OpenFileDescriptor(project, xmlFile.getVirtualFile(), offset);
        d.navigate(true);
    }

    final static Pattern PATTERN_MAPPER_NAMESPACE = Pattern.compile("<mapper\\s+namespace=\"([\\.\\w]+)\".*>");

    /**
     * 找到对应的 mybatis mapper xml文件
     */
    private XmlFile findMybatisMapperXmlFile(Project project, GlobalSearchScope scope, String daoName) {
// 当前项目的所有元素 mapper, 分别填入类型, 作用域 GlobalSearchScope
        Collection<VirtualFile> xmlFiles = FilenameIndex.getAllFilesByExt(project, "xml", scope);
        for (VirtualFile xmlFile : xmlFiles) {
            PsiManager psiManager = PsiManager.getInstance(project);
            PsiFile file = psiManager.findFile(xmlFile);
            String text = file.getText();

            if (text != null) {
                Matcher m = PATTERN_MAPPER_NAMESPACE.matcher(text);
                if (m.find()) {
                    String namespace = m.group(1);

                    String[] split = namespace.split("\\.");
                    if (daoName.equals(split[split.length - 1])) {
                        return (XmlFile) file;
                    }
                }
            }
        }
        return null;
    }

    private String qualifiedNameToSimple(String qualifiedName) {
        String[] split = qualifiedName.split("\\.");
        return split[split.length - 1];
    }

    /**
     * 对话框选择参数类型
     *
     * @param arg
     * @param project
     * @param module
     * @param scope
     * @param importSet
     * @return
     */
    private String[] dialogArgType(String[] arg, Project project, Module module, GlobalSearchScope scope, HashSet<String> importSet) {
        int length = arg.length;
        String[] res = new String[length];
        for (int i = 0; i < length; i++) {
            int typeIndex = messageShow("参数选择", "请为参数" + arg[i] + "选择类型", ARG_LIST);
            if (typeIndex==-1) {
                stopped=true;
            } else if (ARG_DIY_INDEX == typeIndex) {
                PsiClass psiClass = ClassChoose.chooseClass("请为参数" + arg[i] + "选择类型", project, module, scope);
                if (psiClass==null){
                    stopped=true;
                    return new String[0];
                }
                String qualifiedName = psiClass.getQualifiedName();
                res[i] = qualifiedName;
                importSet.add(qualifiedName);
            } else {
                res[i] = ARG_LIST[typeIndex];
            }
        }
        return res;
    }


    /**
     * 单选对话框
     *
     * @param title
     * @param message
     * @param classList
     * @return
     */
    private int messageShow(String title, String message, String[] classList) {
        return Messages.showCheckboxMessageDialog
                (title, message, classList, null, true,
                        0, 0,
                        null
                        , (t, v) -> t);
    }


    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)Service\\s*\\.");

    /**
     * 返回选中的内容所在的行
     *
     * @param model
     * @param editor
     * @return
     */
    private String getLineText(SelectionModel model, Editor editor) {
        int offset = model.getLeadSelectionOffset();
        Document document = editor.getDocument();
        int line = document.getLineNumber(offset);
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        return document.getText(new TextRange(start, end));
    }

    /**
     * 根据文本获取选中的方法的调用者——service
     *
     * @param lineText
     * @return
     */
    private String parseServiceName(String lineText) {
        Matcher matcher = SERVICE_NAME_PATTERN.matcher(lineText);
        if (matcher.find())
            return matcher.group(1);
        return "";
    }

    /**
     * 根据文本获取选中的方法的参数
     *
     * @param lineText
     * @param methodName
     * @return
     */
    private String[] parseArg(String lineText, String methodName) {
        final Pattern argPattern = Pattern.compile(methodName + "\\s*\\((.*?)\\)");
        Matcher matcher = argPattern.matcher(lineText);
        if (matcher.find()) {
            String args = matcher.group(1);
            if ("".equals(args.trim()))
                return new String[0];
            return args.split(",");
        }
        return new String[0];
    }

    /**
     * 消息提示
     *
     * @param editor
     * @param result
     */
    private void showPopupBalloon(final Editor editor, final String result) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                JBPopupFactory factory = JBPopupFactory.getInstance();
                factory.createHtmlTextBalloonBuilder(result, null, new JBColor(new Color(186, 238, 186), new Color(73, 117, 73)), null)
                        .setFadeoutTime(5000)
                        .createBalloon()
                        .show(factory.guessBestPopupLocation(editor), Balloon.Position.below);
            }
        });
    }


    /**
     * 判断是否是mapper.xml
     *
     * @param file
     * @return
     */
    private static boolean isMapperXml(@Nullable PsiFile file) {
        if (!isXmlFile(file)) {
            return false;
        } else {
            XmlTag rootTag = ((XmlFile) file).getRootTag();
            return null != rootTag && rootTag.getName().equals("mapper");
        }
    }

    /**
     * 判断是否是xml文件
     *
     * @param file
     * @return
     */
    private static boolean isXmlFile(@Nullable PsiFile file) {
        if (file == null) {
            return false;
        }

        return file instanceof XmlFile;
    }


    private void dialog(Project project) {
        TypeChooseDialog dialog = new TypeChooseDialog(project, "参数类型选择") {
            @Override
            protected @Nullable JComponent createCenterPanel() {
                JPanel dialogPanel = new JPanel(new BorderLayout());
                JLabel label = new JLabel("testing");
                label.setPreferredSize(new Dimension(100, 100));
                dialogPanel.add(label, BorderLayout.CENTER);
                return dialogPanel;
            }
        };
        dialog.showAndGet();
    }


    private void popup(List<String> classList, AnActionEvent e) {
        final JBList list = new JBList(classList);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer());

        JBPopupFactory.getInstance()
                .createListPopupBuilder(list)
                .setTitle("Choose test class to run")
//                .setMovable(false)
//                .setResizable(false)
//                .setRequestFocus(true)
//                .setCancelOnWindowDeactivation(false)
                .setItemChoosenCallback(() -> {
//                    selectedValue = (String) list.getSelectedValue();
//                    popFinished.set(true);
                })
                .createPopup()
                .showInBestPositionFor(e.getDataContext());
    }
}