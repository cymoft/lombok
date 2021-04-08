package lombok.javac.handlers;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.JavacTreeMaker.TypeTag.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Arrays;
import java.util.Iterator;

import javax.lang.model.type.TypeKind;

import lombok.core.AnnotationValues;
import lombok.core.HandlerPriority;
import lombok.core.LombokImmutableList;
import lombok.extern.validation.ValidateMethod;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TypeTag;
import lombok.spi.Provides;

@Provides
public class HandleValidateMethod extends JavacAnnotationHandler<ValidateMethod> {

	@Override public void handle(AnnotationValues<ValidateMethod> annotation, JCAnnotation ast, JavacNode annotationNode) {
		deleteAnnotationIfNeccessary(annotationNode, ValidateMethod.class);
		JavacNode methodNode = annotationNode.up();
		
		if (!isBooleanMethod(methodNode)) {
			annotationNode.addError("@ValidateMethod is only supported on a method which return a boolean.");
			return;
		}
		
		JavacNode typeNode = methodNode.up();

		// define names
		String methodName = methodNode.getName();
		String validAnnoTypeName = String.format("_%s_validation_annotation", methodName);
		String validClassName = String.format("_%s_validation_validator", methodName);
	
		
		JavacNode validAnnoTypeNode = generateAndInjectValidAnnotationType(typeNode,annotationNode, validAnnoTypeName, validClassName);
		
		generateAndInjectValidatorClass(validAnnoTypeNode, typeNode, annotationNode, methodNode, validClassName);
				
		addAnnotationToBean(typeNode, annotationNode, validAnnoTypeName);

	}
	
	public static JavacNode generateAndInjectValidAnnotationType(JavacNode typeNode, JavacNode annotationNode, String validAnnoTypeName, String validClassName) {
		JavacTreeMaker typemaker = typeNode.getTreeMaker();

		// generate validation annotation
		// generate override isValid method in validator class
		JCMethodDecl messageEle = typemaker.MethodDef(
			typemaker.Modifiers(Flags.PUBLIC | Flags.ABSTRACT), 
			typeNode.toName("message"), 
			chainDots(typeNode, "java", "lang", "String"),
			List.<JCTypeParameter>nil(),
			List.<JCVariableDecl>nil(), 
			List.<JCExpression>nil(),
			null,
			typemaker.Literal(""));
		
		
		JCMethodDecl groupsEle = typemaker.MethodDef(
			typemaker.Modifiers(Flags.PUBLIC | Flags.ABSTRACT), 
			typeNode.toName("groups"), 
			typemaker.TypeArray(
				typemaker.TypeApply(
					chainDots(typeNode, "java", "lang", "Class"), 
					List.<JCExpression>of(
						typemaker.Wildcard(
							typemaker.TypeBoundKind(BoundKind.UNBOUND), 
							null)
						)
					)
				),
			List.<JCTypeParameter>nil(),
			List.<JCVariableDecl>nil(), 
			List.<JCExpression>nil(),
			null,
			typemaker.NewArray(null, List.<JCExpression>nil(), List.<JCExpression>nil()));
		
		
		JCMethodDecl payloadEle = typemaker.MethodDef(
			typemaker.Modifiers(Flags.PUBLIC | Flags.ABSTRACT), 
			typeNode.toName("payload"), 
			typemaker.TypeArray(
				typemaker.TypeApply(
					chainDots(typeNode, "java", "lang", "Class"), 
					List.<JCExpression>of(
						typemaker.Wildcard(
							typemaker.TypeBoundKind(BoundKind.EXTENDS), 
							chainDots(typeNode, "javax", "validation", "Payload")
							)
						)
					)
				),
			List.<JCTypeParameter>nil(),
			List.<JCVariableDecl>nil(), 
			List.<JCExpression>nil(),
			null,
			typemaker.NewArray(null, List.<JCExpression>nil(), List.<JCExpression>nil()));

		
		JCModifiers validAnnoTypeModifiers = typemaker.Modifiers(Flags.STATIC | Flags.ANNOTATION | Flags.INTERFACE);
		JCClassDecl validAnnoTypeDef = typemaker.ClassDef(validAnnoTypeModifiers, typeNode.toName(validAnnoTypeName), List.<JCTypeParameter>nil(), null, List.<JCExpression>nil(), List.<JCTree>of(messageEle, groupsEle, payloadEle));
		
		JavacNode validAnnoTypeNode = injectType(typeNode, validAnnoTypeDef);
		
		
		// add java.lang.annotation.Target to validation annotation
		JCExpression targetIsType = chainDots(validAnnoTypeNode, "java","lang","annotation","ElementType","TYPE");
		JCModifiers mods = ((JCClassDecl)validAnnoTypeNode.get()).mods;
		JavacTreeMaker validAnnoTypeMaker = validAnnoTypeNode.getTreeMaker();
		
		addAnnotation(mods, validAnnoTypeNode, annotationNode, "java.lang.annotation.Target", targetIsType);		

		// add java.lang.annotation.Retention to validation annotation
		JCExpression retentionPolicyIsRuntime = chainDots(validAnnoTypeNode, "java","lang","annotation","RetentionPolicy","RUNTIME");
		addAnnotation(mods, validAnnoTypeNode, annotationNode, "java.lang.annotation.Retention", retentionPolicyIsRuntime);	

		// add javax.validation.Constraint to validation annotation
		JCExpression validatedBy = validAnnoTypeMaker.Ident(validAnnoTypeNode.toName("validatedBy"));
		JCExpression validClassNameClass = chainDots(validAnnoTypeNode,validAnnoTypeName,validClassName,"class" );
		JCAssign constraint = validAnnoTypeMaker.Assign(validatedBy, validClassNameClass);
		addAnnotation(mods, validAnnoTypeNode, annotationNode, "javax.validation.Constraint", constraint);			
		return validAnnoTypeNode;
	}

	public static JavacNode generateAndInjectValidatorClass(JavacNode validAnnoTypeNode, JavacNode typeNode, JavacNode annotationNode, JavacNode methodNode,String validClassName) {
		JavacTreeMaker validAnnoTypeMaker = validAnnoTypeNode.getTreeMaker();
		
		// generate validator class
		JCExpression chainDots = chainDots(validAnnoTypeNode, "javax","validation","ConstraintValidator");
		JCTypeApply typeApply = validAnnoTypeMaker.TypeApply(chainDots, List.of(cloneSelfType(validAnnoTypeNode), cloneSelfType(typeNode)));

		JCModifiers validClassModifiers = validAnnoTypeMaker.Modifiers(Flags.STATIC);
		
		JCClassDecl validClassDef = validAnnoTypeMaker.ClassDef(
			validClassModifiers, 
			validAnnoTypeNode.toName(validClassName), 
			List.<JCTypeParameter>nil(), 
			null, 
			List.of((JCExpression)typeApply),
			List.<JCTree>nil());
		JavacNode validClassTypeNode = injectType(validAnnoTypeNode, validClassDef);
		
		JavacTreeMaker validClassMaker = validClassTypeNode.getTreeMaker();
		
		// generate override isValid method in validator class
		JCVariableDecl varDef1 = validClassMaker.VarDef(
			validClassMaker.Modifiers(Flags.PARAMETER), 
			validClassTypeNode.toName("bean") , 
			cloneSelfType(typeNode), 
			null);
		JCVariableDecl varDef2 = validClassMaker.VarDef(
			validClassMaker.Modifiers(Flags.PARAMETER), 
			validClassTypeNode.toName("constraintValidatorContext"), 
			chainDots(validClassTypeNode,"javax","validation","ConstraintValidatorContext"), 
			null);
		
		JCMethodDecl methodDef = validClassMaker.MethodDef(
			validClassMaker.Modifiers(Flags.PUBLIC), 
			validClassTypeNode.toName("isValid"), 
			validClassMaker.TypeIdent(CTC_BOOLEAN),
			List.<JCTypeParameter>nil(),
			List.of(varDef1,varDef2), 
			List.<JCExpression>nil(),
			validClassMaker.Block(0, List.<JCStatement>of(validClassMaker.Return(createMethodAccessor(validClassMaker, methodNode, validAnnoTypeMaker.Ident(validClassTypeNode.toName("bean")))))),
			null);
		
		// important: inject method will fail without below statement
		recursiveSetGeneratedBy(methodDef, annotationNode);
		
		injectMethod(validClassTypeNode, methodDef);
		
		return validClassTypeNode;
	}

	public static void addAnnotationToBean(JavacNode typeNode, JavacNode annotationNode, String validAnnoTypeName) {
		JavacTreeMaker typemaker = typeNode.getTreeMaker();
		// add generated validation annotation to class
		JCAnnotation cAnnotation = (JCAnnotation)annotationNode.get();		
		JCClassDecl jcTree = (JCClassDecl)typeNode.get();
		List<JCExpression> argList;
		if(cAnnotation.getArguments() != null) {
			JCExpression[] argArr = new JCExpression[cAnnotation.getArguments().size()];
			for (int i = 0; i < cAnnotation.getArguments().size(); i++) {
				 JCExpression jcExpression = cAnnotation.getArguments().get(i);
				 if(jcExpression instanceof JCAssign) {
					 JCAssign assign = (JCAssign)jcExpression;
					 argArr[i] = typemaker.Assign(
						 cloneType(typemaker,assign.lhs,annotationNode), 
						 cloneType(typemaker,assign.rhs,annotationNode));

					 recursiveSetGeneratedBy(argArr[i], annotationNode);
				 }else {
					 argArr[i] = cloneType(typemaker,cAnnotation.getArguments().get(i), annotationNode);	 
				 }
			}
			argList = List.from(argArr);
		}else {
			argList = null;
		}
		

		addAnnotationWithArgList(jcTree.mods, typeNode, annotationNode, typeNode.getName() + "." + validAnnoTypeName, argList);	
	}
	
	public static JCFieldAccess selfType(JavacNode typeNode) {
		JavacTreeMaker maker = typeNode.getTreeMaker();
		Name name = ((JCClassDecl) typeNode.get()).name;
		return maker.Select(maker.Ident(name), typeNode.toName("class"));
	}
	
	public static boolean isBooleanMethod(JavacNode methodNode) {
		if(methodNode.get() instanceof JCMethodDecl) {
			JCMethodDecl methodDecl = (JCMethodDecl)methodNode.get();
			JCTree returnType = methodDecl.getReturnType();
			if(returnType instanceof JCPrimitiveTypeTree) {
				JCPrimitiveTypeTree primitiveTypeTree = (JCPrimitiveTypeTree)returnType;
				if(primitiveTypeTree.getPrimitiveTypeKind().equals(TypeKind.BOOLEAN)) {
					return true;
 				}
			}
			
		}
		return false;
	}
		
	// copy from JavacHandlerUtil, change arg to argList
	public static void addAnnotationWithArgList(JCModifiers mods, JavacNode node, JavacNode source, String annotationTypeFqn, List<JCExpression> argList) {
		boolean isJavaLangBased;
		String simpleName; {
			int idx = annotationTypeFqn.lastIndexOf('.');
			simpleName = idx == -1 ? annotationTypeFqn : annotationTypeFqn.substring(idx + 1);
			
			isJavaLangBased = idx == 9 && annotationTypeFqn.regionMatches(0, "java.lang.", 0, 10);
		}
		
		for (JCAnnotation ann : mods.annotations) {
			JCTree annType = ann.getAnnotationType();
			if (annType instanceof JCIdent) {
				Name lastPart = ((JCIdent) annType).name;
				if (lastPart.contentEquals(simpleName)) return;
			}
			
			if (annType instanceof JCFieldAccess) {
				if (annType.toString().equals(annotationTypeFqn)) return;
			}
		}
		
		JavacTreeMaker maker = node.getTreeMaker();
		JCExpression annType = isJavaLangBased ? genJavaLangTypeRef(node, simpleName) : chainDotsString(node, annotationTypeFqn);
		argList = argList != null ? argList : List.<JCExpression>nil();
		JCAnnotation annotation = recursiveSetGeneratedBy(maker.Annotation(annType, argList), source);
		mods.annotations = mods.annotations.append(annotation);
	}

	
	
}
