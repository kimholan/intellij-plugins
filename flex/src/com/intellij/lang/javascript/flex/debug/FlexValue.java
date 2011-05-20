package com.intellij.lang.javascript.flex.debug;

import com.intellij.javascript.JSDebuggerSupportUtils;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.index.JavaScriptIndex;
import com.intellij.lang.javascript.psi.*;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement;
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NullableComputable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Icons;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.*;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

class FlexValue extends XValue {
  private FlexStackFrame myFlexStackFrame;
  private final FlexDebugProcess myDebugProcess;
  private final XSourcePosition mySourcePosition;

  private final String myName;
  private final String myExpression;
  private final String myResult;
  private @Nullable final String myParentResult;
  private final ValueType myValueType;
  private Icon myPreferredIcon;

  private static final String OBJECT_MARKER = "Object ";
  private static final int MAX_STRING_LENGTH_TO_SHOW = XValueNode.MAX_VALUE_LENGTH;
  static final String TEXT_MARKER = " text ";
  static final String ELEMENT_MARKER = " element ";
  private static final String ESCAPE_START = "IDEA-ESCAPE-START";
  private static final String ESCAPE_END = "IDEA-ESCAPE-END";

  private static final Comparator<XValue> ourArrayElementsComparator = new Comparator<XValue>() {
    public int compare(XValue o1, XValue o2) {
      if (o1 instanceof FlexValue && o2 instanceof FlexValue) {
        String name = ((FlexValue)o1).myName;
        String name2 = ((FlexValue)o2).myName;

        if (!StringUtil.isEmpty(name) &&
            !StringUtil.isEmpty(name2) &&
            Character.isDigit(name.charAt(0)) &&
            Character.isDigit(name2.charAt(0))
          ) {
          try {
            return Integer.parseInt(name) - Integer.parseInt(name2);
          }
          catch (NumberFormatException ignore) {/**/}
        }

        if (name == null) {
          return name2 == null ? 0 : -1;
        }
        else if (name2 == null) {
          return 1;
        }
        return name.compareToIgnoreCase(name2);
      }
      return 1;
    }
  };

  static enum ValueType {
    This(Icons.CLASS_ICON),
    Parameter(Icons.PARAMETER_ICON),
    Variable(Icons.VARIABLE_ICON),
    Field(Icons.FIELD_ICON),
    ScopeChainEntry(Icons.CLASS_INITIALIZER),
    Other(null);

    private @Nullable final Icon myIcon;

    private ValueType(final @Nullable Icon icon) {
      myIcon = icon;
    }
  }

  FlexValue(final FlexStackFrame flexStackFrame,
            final FlexDebugProcess flexDebugProcess,
            final XSourcePosition sourcePosition,
            final String name,
            final String expression,
            final String result,
            final @Nullable String parentResult,
            final @NotNull ValueType valueType) {
    myFlexStackFrame = flexStackFrame;
    myDebugProcess = flexDebugProcess;
    mySourcePosition = sourcePosition;
    myName = name;
    myExpression = expression;
    myResult = unescape(result);
    myParentResult = parentResult;
    myValueType = valueType;
  }

  String getResult() {
    return myResult;
  }

  @Override
  public String getEvaluationExpression() {
    return myExpression;
  }

  public void setPreferredIcon(final Icon preferredIcon) {
    myPreferredIcon = preferredIcon;
  }

  public void computePresentation(@NotNull final XValueNode node) {
    final boolean isObject = myResult.contains(OBJECT_MARKER);
    String val;
    String type = null;
    String additionalInfo = null;

    if (isObject) {
      val = myResult;
      final Pair<String, String> classNameAndAdditionalInfo = getTypeAndAdditionalInfo(myResult);
      type = classNameAndAdditionalInfo.first;
      additionalInfo = classNameAndAdditionalInfo.second;

      if (type != null) {
        val = "[".concat(getObjectId(myResult, myResult.indexOf(OBJECT_MARKER), OBJECT_MARKER)).concat("]");
      }
    }
    else {
      val = myResult;
    }

    if (("XML".equals(type) || "XMLList".equals(type)) && myExpression.indexOf('=') == -1) {
      if (myDebugProcess.isDebuggerFromSdk4()) {
        final String finalType = type;
        final FlexStackFrame.EvaluateCommand
          command = myFlexStackFrame.new EvaluateCommand(myExpression + ".toXMLString()", new XDebuggerEvaluator.XEvaluationCallback() {
          public void evaluated(@NotNull XValue result) {
            setResult(((FlexValue)result).myResult, node, finalType, isObject);
          }

          public void errorOccurred(@NotNull String errorMessage) {
            setResult(errorMessage, node, finalType, isObject);
          }

          private void setResult(String s, XValueNode node, String finalType, boolean b) {
            if (!node.isObsolete()) {
              s = setFullValueEvaluatorIfNeeded(node, s, true);
              node.setPresentation(myValueType.myIcon, finalType, s, b);
            }
          }
        });
        myDebugProcess.addPendingCommand(new CompositeDebuggerCommand(node, command), 700);
        return;
      }

      else if (myDebugProcess.isDebuggerFromSdk3()) {
        if ("XMLList".equals(type)) {
          node.setFullValueEvaluator(new XFullValueEvaluator(FlexBundle.message("debugger.show.full.value")) {
            public void startEvaluation(@NotNull XFullValueEvaluationCallback callback) {
              new XmlObjectEvaluator(FlexValue.this, callback).startEvaluation();
            }
          });

          node.setPresentation(myValueType.myIcon, type, val.concat(" "), isObject);
          return;
        }
        else if (additionalInfo != null) {
          /*
            additionalInfo may look like following:
            "text element content"
            "element <root attr=\"attrValue\">"
            "element <child/>"
          */
          final boolean isElement = additionalInfo.startsWith(ELEMENT_MARKER + "<") && additionalInfo.endsWith(">");
          final boolean isEmptyElement = isElement && additionalInfo.endsWith("/>");
          final boolean isText = !isElement && additionalInfo.startsWith(TEXT_MARKER);

          if (isText || isElement) {
            String textToShow;

            if (isText) {
              textToShow = additionalInfo.substring(TEXT_MARKER.length());
            }
            else if (isEmptyElement) {
              textToShow = additionalInfo.substring(ELEMENT_MARKER.length());
            }
            else {
              final String startTag = additionalInfo.substring(ELEMENT_MARKER.length());

              final int spaceIndex = startTag.indexOf(" ");
              final String tagName = startTag.substring(1, spaceIndex > 0 ? spaceIndex : startTag.length() - 1);
              textToShow = startTag + "..." + "</" + tagName + "> ";
              if (textToShow.length() > MAX_STRING_LENGTH_TO_SHOW) {
                textToShow = textToShow.substring(0, MAX_STRING_LENGTH_TO_SHOW).concat("... ");
              }

              node.setFullValueEvaluator(new XFullValueEvaluator(FlexBundle.message("debugger.show.full.value")) {
                public void startEvaluation(@NotNull XFullValueEvaluationCallback callback) {
                  new XmlObjectEvaluator(FlexValue.this, callback).startEvaluation();
                }
              });

              node.setPresentation(myValueType.myIcon, type, textToShow, isObject);
              return;
            }

            textToShow = setFullValueEvaluatorIfNeeded(node, textToShow, true);
            node.setPresentation(myValueType.myIcon, type, textToShow, isObject);
            return;
          }

          val = setFullValueEvaluatorIfNeeded(node, additionalInfo, true);
          node.setPresentation(myValueType.myIcon, type, val, isObject);
          return;
        }
      }
    }

    val = setFullValueEvaluatorIfNeeded(node, val, false);
    node.setPresentation(myPreferredIcon == null ? myValueType.myIcon : myPreferredIcon, type, val, isObject);
  }

  private static String setFullValueEvaluatorIfNeeded(final XValueNode node, String value, final boolean isXml) {
    final String fullValue = value;

    final int lfIndex = fullValue.indexOf('\n');
    final int crIndex = fullValue.indexOf('\r');

    if (fullValue.length() > MAX_STRING_LENGTH_TO_SHOW ||
        lfIndex > -1 && lfIndex < fullValue.length() - 1 ||
        crIndex > -1 && crIndex < fullValue.length() - 1) {

      final boolean quoted = fullValue.charAt(0) == '\'' && fullValue.charAt(fullValue.length() - 1) == '\'';
      final boolean doubleQuoted = fullValue.charAt(0) == '\"' && fullValue.charAt(fullValue.length() - 1) == '\"';

      if (value.length() > MAX_STRING_LENGTH_TO_SHOW) {
        final String ending = doubleQuoted ? "\" " : quoted ? "\' " : " ";
        value = value.substring(0, MAX_STRING_LENGTH_TO_SHOW).concat("...").concat(ending);
      }
      else if (!value.endsWith(" ")) {
        value = value.concat(" ");  // just a separator between text value and hyperlink
      }

      final String unquoted = quoted || doubleQuoted ? fullValue.substring(1, fullValue.length() - 1) : fullValue;
      node.setFullValueEvaluator(
        new XFullValueEvaluator(FlexBundle.message("debugger.show.full.value")) {
          public void startEvaluation(@NotNull XFullValueEvaluationCallback callback) {
            callback.evaluated(unquoted, isXml ? XmlObjectEvaluator.MONOSPACED_FONT : null);
          }
        });
    }

    return value;
  }

  @Override
  public XValueModifier getModifier() {
    return new XValueModifier() {
      @Override
      public void setValue(@NotNull String _expression, @NotNull final XModificationCallback callback) {
        FlexStackFrame.EvaluateCommand command = myFlexStackFrame.new EvaluateCommand(myExpression + "=" + _expression, null) {
          protected void dispatchResult(String s) {
            super.dispatchResult(s);
            callback.valueModified();
          }
        };
        myDebugProcess.sendCommand(command);
      }
    };
  }

  @Override
  public void computeChildren(@NotNull final XCompositeNode node) {
    final int i = myResult.indexOf(OBJECT_MARKER);
    if (i == -1) super.computeChildren(node);

    final String type = getTypeAndAdditionalInfo(myResult).first;

    final FlexStackFrame.EvaluateCommand
      command = myFlexStackFrame.new EvaluateCommand(referenceObjectBase(i, OBJECT_MARKER), null) {
      @Override
      CommandOutputProcessingMode doOnTextAvailable(@NonNls final String resultS) {
        StringTokenizer tokenizer = new StringTokenizer(resultS, "\r\n");

        // skip first token; it contains $-prefix followed by myResult: $6 = [Object 30860193, class='__AS3__.vec::Vector.<String>']
        tokenizer.nextToken();

        final LinkedHashMap<String, FlexValue> fieldNameToFlexValueMap = new LinkedHashMap<String, FlexValue>(tokenizer.countTokens());

        final NodeClassInfo nodeClassInfo = ApplicationManager.getApplication().runReadAction(new NullableComputable<NodeClassInfo>() {
          @Nullable
          public NodeClassInfo compute() {
            final Project project = myDebugProcess.getSession().getProject();
            final JSClass jsClass = mySourcePosition == null
                                    ? null
                                    : findJSClass(project,
                                                  ModuleUtil.findModuleForFile(mySourcePosition.getFile(), project), type);
            return jsClass == null ? null : NodeClassInfo.getNodeClassInfo(jsClass);
          }
        });

        while (tokenizer.hasMoreElements()) {
          final String s = tokenizer.nextToken().trim();
          if (s.length() == 0) continue;
          final int delimIndex = s.indexOf(FlexStackFrame.DELIM);
          if (delimIndex == -1) {
            FlexDebugProcess.log("Unrecognized string:" + s);
            continue;
          }
          final String fieldName = s.substring(0, delimIndex);
          final String result = s.substring(delimIndex + FlexStackFrame.DELIM.length());

          if (result.startsWith("[Setter ")) {
            // such values do not give any useful information:
            // [Setter 62, name='Child@3d613bb::staticSetter']
            // [Setter 78]
            continue;
          }

          String evaluatedPath = myExpression;

          if (fieldName.length() > 0 && Character.isDigit(fieldName.charAt(0))) {
            evaluatedPath += "[\"" + fieldName + "\"]";
          }
          else {
            evaluatedPath += "." + fieldName;
          }
          // either parameter of static function from scopechain or a field. Static functions from scopechain look like following:
          // // [Object 52571545, class='Main$/staticFunction']
          final ValueType valueType = type != null && type.indexOf('/') > -1 ? ValueType.Parameter : ValueType.Field;
          final FlexValue flexValue =
            new FlexValue(myFlexStackFrame, myDebugProcess, mySourcePosition, fieldName, evaluatedPath, result, FlexValue.this.myResult,
                          valueType);

          addValueCheckingDuplicates(flexValue, fieldNameToFlexValueMap);
        }

        addChildren(node, fieldNameToFlexValueMap, nodeClassInfo);

        return CommandOutputProcessingMode.DONE;
      }
    };

    myDebugProcess.sendCommand(command);
  }

  public void computeSourcePosition(@NotNull final XNavigatable navigatable) {
    if (mySourcePosition == null) {
      navigatable.setSourcePosition(null);
      return;
    }

    XSourcePosition result = null;
    final Project project = myDebugProcess.getSession().getProject();

    if (myValueType == ValueType.Variable) {
      final PsiElement contextElement =
        JSDebuggerSupportUtils
          .getContextElement(mySourcePosition.getFile(), mySourcePosition.getOffset(), project);
      final JSFunction jsFunction = PsiTreeUtil.getParentOfType(contextElement, JSFunction.class);

      if (jsFunction != null) {
        final Ref<JSVariable> varRef = new Ref<JSVariable>();
        jsFunction.accept(new JSElementVisitor() {
          public void visitJSElement(final JSElement node) {
            if (varRef.isNull()) {
              node.acceptChildren(this);
            }
          }

          public void visitJSVariable(final JSVariable node) {
            if (myName.equals(node.getName())) {
              varRef.set(node);
            }
            super.visitJSVariable(node);
          }
        });

        if (!varRef.isNull()) {
          result = calcSourcePosition(varRef.get());
        }
      }
    }
    else if (myValueType == ValueType.Parameter) {
      final PsiElement contextElement =
        JSDebuggerSupportUtils
          .getContextElement(mySourcePosition.getFile(), mySourcePosition.getOffset(), project);
      final JSFunction jsFunction = PsiTreeUtil.getParentOfType(contextElement, JSFunction.class);
      final JSParameterList parameterList = jsFunction == null ? null : jsFunction.getParameterList();
      final JSParameter[] parameters = parameterList == null ? JSParameter.EMPTY_ARRAY : parameterList.getParameters();
      for (final JSParameter parameter : parameters) {
        if (myName.equals(parameter.getName())) {
          result = calcSourcePosition(parameter);
          break;
        }
      }
    }
    else if (myValueType == ValueType.Field && myParentResult != null) {
      final String type = getTypeAndAdditionalInfo(myParentResult).first;
      final JSClass jsClass = findJSClass(project, ModuleUtil.findModuleForFile(mySourcePosition.getFile(), project), type);

      if (jsClass != null) {
        result = calcSourcePosition(findFieldOrGetter(myName, jsClass, true));
      }
    }
    navigatable.setSourcePosition(result);
  }

  @Nullable
  private static XSourcePosition calcSourcePosition(final JSQualifiedNamedElement element) {
    if (element != null) {
      final PsiElement navigationElement = element.getNavigationElement();
      final VirtualFile file = navigationElement.getContainingFile().getVirtualFile();
      if (file != null) {
        return XDebuggerUtil.getInstance().createPositionByOffset(file, navigationElement.getTextOffset());
      }
    }
    return null;
  }

  private static void addValueCheckingDuplicates(final FlexValue flexValue,
                                                 final LinkedHashMap<String, FlexValue> fieldNameToFlexValueMap) {
    final String name = flexValue.myName;
    FlexValue existingValue;

    if ((existingValue = fieldNameToFlexValueMap.get("_" + name)) != null &&
        existingValue.getResult().equals(flexValue.getResult())) {
      fieldNameToFlexValueMap.remove("_" + name);
    }
    else if (name.startsWith("_") &&
             name.length() > 1 &&
             (existingValue = fieldNameToFlexValueMap.get(name.substring(1))) != null &&
             existingValue.getResult().equals(flexValue.getResult())) {
      return;
    }

    fieldNameToFlexValueMap.put(name, flexValue);
  }

  private static void addChildren(final XCompositeNode node,
                                  final LinkedHashMap<String, FlexValue> fieldNameToFlexValueMap,
                                  final @Nullable NodeClassInfo nodeClassInfo) {
    final List<FlexValue> elementsOfCollection = new LinkedList<FlexValue>();
    final XValueChildrenList ownStaticFields = new XValueChildrenList();
    final XValueChildrenList ownStaticProperties = new XValueChildrenList();
    final XValueChildrenList ownFields = new XValueChildrenList();
    final XValueChildrenList ownProperties = new XValueChildrenList();
    final XValueChildrenList inheritedStaticFields = new XValueChildrenList();
    final XValueChildrenList inheritedStaticProperties = new XValueChildrenList();
    final XValueChildrenList inheritedFields = new XValueChildrenList();
    final XValueChildrenList inheritedProperties = new XValueChildrenList();

    for (final Map.Entry<String, FlexValue> entry : fieldNameToFlexValueMap.entrySet()) {
      final String name = entry.getKey();
      final FlexValue flexValue = entry.getValue();

      if (isInteger(name)) {
        elementsOfCollection.add(flexValue);
        continue;
      }

      if (nodeClassInfo == null) {
        ownFields.add(name, flexValue);
      }
      else {
        if (updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myOwnStaticFields, ownStaticFields) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myOwnStaticProperties, ownStaticProperties) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myOwnFields, ownFields) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myOwnProperties, ownProperties) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myInheritedStaticFields, inheritedStaticFields) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myInheritedStaticProperties, inheritedStaticProperties) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myInheritedFields, inheritedFields) ||
            updateIconAndAddToListIfMatches(name, flexValue, nodeClassInfo.myInheritedProperties, inheritedProperties)) {
          continue;
        }

        (nodeClassInfo.isDynamic ? ownFields : inheritedFields).add(name, flexValue);
      }
    }

    Collections.sort(elementsOfCollection, ourArrayElementsComparator);

    if (inheritedStaticFields.size() + inheritedStaticProperties.size() + inheritedFields.size() + inheritedProperties.size() > 0) {
      final XValueChildrenList inheritedNodeSingletonList =
        getInheritedNodeSingletonList(inheritedStaticFields, inheritedStaticProperties, inheritedFields, inheritedProperties);
      node.addChildren(inheritedNodeSingletonList, false);
    }

    node.addChildren(ownStaticFields, false);
    node.addChildren(ownStaticProperties, false);
    node.addChildren(ownFields, false);
    node.addChildren(ownProperties, false);

    final XValueChildrenList elementsOfCollectionList = new XValueChildrenList();
    for (final FlexValue flexValue : elementsOfCollection) {
      elementsOfCollectionList.add(flexValue.myName, flexValue);
    }
    node.addChildren(elementsOfCollectionList, false);

    node.addChildren(XValueChildrenList.EMPTY, true);
  }

  private static XValueChildrenList getInheritedNodeSingletonList(final XValueChildrenList inheritedStaticFields,
                                                                  final XValueChildrenList inheritedStaticProperties,
                                                                  final XValueChildrenList inheritedFields,
                                                                  final XValueChildrenList inheritedProperties) {
    final XValue inheritedNode = new XValue() {
      public void computePresentation(@NotNull final XValueNode node) {
        node.setPresentation((Icon)null, null, "", "Inherited members", true);
      }

      public void computeChildren(@NotNull final XCompositeNode node) {
        node.addChildren(inheritedStaticFields, false);
        node.addChildren(inheritedStaticProperties, false);
        node.addChildren(inheritedFields, false);
        node.addChildren(inheritedProperties, true);
      }
    };

    final XValueChildrenList inheritedSingleNodeList = new XValueChildrenList();
    inheritedSingleNodeList.add("", inheritedNode);
    return inheritedSingleNodeList;
  }

  private static boolean updateIconAndAddToListIfMatches(final String name,
                                                         final FlexValue flexValue,
                                                         final Map<String, Icon> nameToIconMap,
                                                         final XValueChildrenList list) {
    final Icon icon = nameToIconMap.get(name);
    if (icon != null) {
      flexValue.setPreferredIcon(icon);
      list.add(flexValue.myName, flexValue);
      return true;
    }
    return false;
  }

  private static boolean isInteger(final String s) {
    try {
      Integer.parseInt(s);
      return true;
    }
    catch (NumberFormatException e) {
      return false;
    }
  }

  private String referenceObjectBase(int i, String marker) {
    // expression may have incorrect syntax like x.dict1.-1. (see examples in http://youtrack.jetbrains.net/issue/IDEA-56653)
    // so it is more reliable to use objectId

    //if (myDebugProcess.isDebuggerFromSdk4()) {
    //  return expression + ".";
    //}

    return "#" + getObjectId(myResult, i, marker) + ".";
  }

  private static String getObjectId(String result, int i, String marker) {
    String s = result.substring(i + marker.length(), result.indexOf(','));
    return FlexStackFrame.validObjectId(s);
  }

  private static Pair<String, String> getTypeAndAdditionalInfo(final @Nullable String fdbText) {
    if (fdbText == null) return Pair.create(null, null);

    // [Object 52571545, class='flash.events::MouseEvent']
    // [Object 52571545, class='Main$/staticFunction']
    // [Object 62823129, class='XML@3be9ad9 element <abc/>']
    String type = null;
    String additionalInfo = null;

    final int classIndex = fdbText.indexOf(FlexStackFrame.CLASS_MARKER);
    final int lastQuoteIndex = fdbText.lastIndexOf("'");

    if (classIndex != -1 && lastQuoteIndex > classIndex) {
      int typeStart = classIndex + FlexStackFrame.CLASS_MARKER.length();
      final String inQuotes = fdbText.substring(typeStart, lastQuoteIndex);
      final int atIndex = inQuotes.indexOf("@");
      if (atIndex > 0) {
        type = inQuotes.substring(0, atIndex);
        final int spaceIndex = inQuotes.indexOf(" ", atIndex);
        if (spaceIndex != -1) {
          additionalInfo = inQuotes.substring(spaceIndex, inQuotes.length());
        }
      }
      else {
        type = inQuotes;
      }
    }

    if ("[]".equals(type)) {
      type = "Array";
    }

    return Pair.create(type, additionalInfo);
  }

  @Nullable
  private static JSClass findJSClass(final Project project, final @Nullable Module module, final String typeFromFlexValueResult) {
    if (typeFromFlexValueResult != null && !typeFromFlexValueResult.contains("/")) {
      final String prefix = "__AS3__.vec::";
      final String type =
        typeFromFlexValueResult.startsWith(prefix) ? typeFromFlexValueResult.substring(prefix.length()) : typeFromFlexValueResult;
      final int index = type.indexOf(".<"); // Vector.<int>
      final String fqn = (index > 0 ? type.substring(0, index) : type).replace("::", ".");

      final JavaScriptIndex jsIndex = JavaScriptIndex.getInstance(project);
      PsiElement jsClass = JSResolveUtil.findClassByQName(fqn, jsIndex, module);

      if (!(jsClass instanceof JSClass) && fqn.endsWith("$")) { // fdb adds '$' to class name in case of static context
        jsClass = JSResolveUtil.findClassByQName(fqn.substring(0, fqn.length() - 1), jsIndex, module);
      }

      if (!(jsClass instanceof JSClass) && module != null) {
        // probably this class came from dynamically loaded module that is not in moduleWithDependenciesAndLibrariesScope(module)
        final GlobalSearchScope scope = ProjectScope.getAllScope(project);
        jsClass = JSResolveUtil.findClassByQName(fqn, scope);

        if (!(jsClass instanceof JSClass) && fqn.endsWith("$")) {
          jsClass = JSResolveUtil.findClassByQName(fqn.substring(0, fqn.length() - 1), scope);
        }
      }

      return jsClass instanceof JSClass ? (JSClass)jsClass : null;
    }

    return null;
  }

  @Nullable
  private static JSQualifiedNamedElement findFieldOrGetter(final String name,
                                                           final JSClass jsClass,
                                                           final boolean lookInSupers) {
    return findFieldOrGetter(name, jsClass, lookInSupers, lookInSupers ? new THashSet<JSClass>() : Collections.<JSClass>emptySet());
  }

  @Nullable
  private static JSQualifiedNamedElement findFieldOrGetter(final String name,
                                                           final JSClass jsClass,
                                                           final boolean lookInSupers,
                                                           final Set<JSClass> visited) {
    if (visited.contains(jsClass)) return null;

    final JSVariable field = jsClass.findFieldByName(name);
    if (field != null) return field;

    final JSFunction getter = jsClass.findFunctionByNameAndKind(name, JSFunction.FunctionKind.GETTER);
    if (getter != null) return getter;

    if (lookInSupers) {
      visited.add(jsClass);

      for (final JSClass superClass : jsClass.getSuperClasses()) {
        final JSQualifiedNamedElement inSuper = findFieldOrGetter(name, superClass, lookInSupers, visited);
        if (inSuper != null) {
          return inSuper;
        }
      }
    }

    return null;
  }

  /**
   * Looks for IDEA-ESCAPE-START and IDEA-ESCAPE-END markers in input string and unescapes symbols inside these markers. Markers are removed.
   */
  private static String unescape(String str) {
    int escapeEndIndex = 0;
    int escapeStartIndex;

    while ((escapeStartIndex = str.indexOf(ESCAPE_START, escapeEndIndex - ESCAPE_START.length())) > -1) {
      escapeEndIndex = str.indexOf(ESCAPE_END, escapeStartIndex);
      if (escapeEndIndex < 0) {
        escapeEndIndex = str.length();
      }
      str = str.substring(0, escapeStartIndex) +
            StringUtil.unescapeStringCharacters(str.substring(escapeStartIndex + ESCAPE_START.length(), escapeEndIndex)) +
            (escapeEndIndex + ESCAPE_END.length() <= str.length() ? str.substring(escapeEndIndex + ESCAPE_END.length()) : "");
    }
    return str;
  }
}
