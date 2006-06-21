/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.parser.JavadocTagConstants;

/**
 * Node representing a structured Javadoc comment
 */
public class Javadoc extends ASTNode {

	public JavadocSingleNameReference[] paramReferences; // @param
	public JavadocSingleTypeReference[] paramTypeParameters; // @param
	public TypeReference[] exceptionReferences; // @throws, @exception
	public JavadocReturnStatement returnStatement; // @return
	public Expression[] seeReferences; // @see
	public long inheritedPositions = -1;
	// bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=51600
	// Store param references for tag with invalid syntax
	public JavadocSingleNameReference[] invalidParameters; // @param

	public Javadoc(int sourceStart, int sourceEnd) {
		this.sourceStart = sourceStart;
		this.sourceEnd = sourceEnd;
	}
	
	/*
	 * @see org.eclipse.jdt.internal.compiler.ast.ASTNode#print(int, java.lang.StringBuffer)
	 */
	public StringBuffer print(int indent, StringBuffer output) {
		printIndent(indent, output).append("/**\n"); //$NON-NLS-1$
		if (this.paramReferences != null) {
			for (int i = 0, length = this.paramReferences.length; i < length; i++) {
				printIndent(indent + 1, output).append(" * @param "); //$NON-NLS-1$		
				this.paramReferences[i].print(indent, output).append('\n');
			}
		}
		if (this.paramTypeParameters != null) {
			for (int i = 0, length = this.paramTypeParameters.length; i < length; i++) {
				printIndent(indent + 1, output).append(" * @param <"); //$NON-NLS-1$		
				this.paramTypeParameters[i].print(indent, output).append(">\n"); //$NON-NLS-1$
			}
		}
		if (this.returnStatement != null) {
			printIndent(indent + 1, output).append(" * @"); //$NON-NLS-1$
			this.returnStatement.print(indent, output).append('\n');
		}
		if (this.exceptionReferences != null) {
			for (int i = 0, length = this.exceptionReferences.length; i < length; i++) {
				printIndent(indent + 1, output).append(" * @throws "); //$NON-NLS-1$		
				this.exceptionReferences[i].print(indent, output).append('\n');
			}
		}
		if (this.seeReferences != null) {
			for (int i = 0, length = this.seeReferences.length; i < length; i++) {
				printIndent(indent + 1, output).append(" * @see "); //$NON-NLS-1$		
				this.seeReferences[i].print(indent, output).append('\n');
			}
		}
		printIndent(indent, output).append(" */\n"); //$NON-NLS-1$
		return output;
	}

	/*
	 * Resolve type javadoc while a class scope
	 */
	public void resolve(ClassScope scope) {

		// @param tags
		int paramTagsSize = this.paramReferences == null ? 0 : this.paramReferences.length;
		for (int i = 0; i < paramTagsSize; i++) {
			JavadocSingleNameReference param = this.paramReferences[i];
			scope.problemReporter().javadocUnexpectedTag(param.tagSourceStart, param.tagSourceEnd);
		}
		resolveTypeParameterTags(scope, true);

		// @return tags
		if (this.returnStatement != null) {
			scope.problemReporter().javadocUnexpectedTag(this.returnStatement.sourceStart, this.returnStatement.sourceEnd);
		}

		// @throws/@exception tags
		int throwsTagsLength = this.exceptionReferences == null ? 0 : this.exceptionReferences.length;
		for (int i = 0; i < throwsTagsLength; i++) {
			TypeReference typeRef = this.exceptionReferences[i];
			int start, end;
			if (typeRef instanceof JavadocSingleTypeReference) {
				JavadocSingleTypeReference singleRef = (JavadocSingleTypeReference) typeRef;
				start = singleRef.tagSourceStart;
				end = singleRef.tagSourceEnd;
			} else if (typeRef instanceof JavadocQualifiedTypeReference) {
				JavadocQualifiedTypeReference qualifiedRef = (JavadocQualifiedTypeReference) typeRef;
				start = qualifiedRef.tagSourceStart;
				end = qualifiedRef.tagSourceEnd;
			} else {
				start = typeRef.sourceStart;
				end = typeRef.sourceEnd;
			}
			scope.problemReporter().javadocUnexpectedTag(start, end);
		}

		// @see tags
		int seeTagsLength = this.seeReferences == null ? 0 : this.seeReferences.length;
		for (int i = 0; i < seeTagsLength; i++) {
			resolveReference(this.seeReferences[i], scope);
		}
	}
	
	/*
	 * Resolve method javadoc while a method scope
	 */
	public void resolve(MethodScope methScope) {
		
		// get method declaration
		AbstractMethodDeclaration methDecl = methScope.referenceMethod();
		boolean overriding = methDecl == null || methDecl.binding == null ? false : !methDecl.binding.isStatic() && ((methDecl.binding.modifiers & (ExtraCompilerModifiers.AccImplementing | ExtraCompilerModifiers.AccOverriding)) != 0);

		// @see tags
		int seeTagsLength = this.seeReferences == null ? 0 : this.seeReferences.length;
		boolean superRef = false;
		for (int i = 0; i < seeTagsLength; i++) {
			
			// Resolve reference
			resolveReference(this.seeReferences[i], methScope);
			
			// see whether we can have a super reference
			try {
				if (methDecl != null && (methDecl.isConstructor() || overriding) && !superRef) {
					if (this.seeReferences[i] instanceof JavadocMessageSend) {
						JavadocMessageSend messageSend = (JavadocMessageSend) this.seeReferences[i];
						// if binding is valid then look if we have a reference to an overriden method/constructor
						if (messageSend.binding != null && messageSend.binding.isValidBinding() && messageSend.actualReceiverType instanceof ReferenceBinding) {
							ReferenceBinding methodReceiverType = (ReferenceBinding) messageSend.actualReceiverType;
							if ((methodReceiverType.isSuperclassOf(methDecl.binding.declaringClass) || (methodReceiverType.isInterface() && methDecl.binding.declaringClass.implementsInterface(methodReceiverType, true))) &&
								CharOperation.equals(messageSend.selector, methDecl.selector) &&
								(methDecl.binding.returnType.isCompatibleWith(messageSend.binding.returnType))) {
								if (messageSend.arguments == null && methDecl.arguments == null) {
									superRef = true;
								}
								else if (messageSend.arguments != null && methDecl.arguments != null) {
									superRef = methDecl.binding.areParameterErasuresEqual(messageSend.binding);
								}
							}
						}
					}
					else if (this.seeReferences[i] instanceof JavadocAllocationExpression) {
						JavadocAllocationExpression allocationExpr = (JavadocAllocationExpression) this.seeReferences[i];
						// if binding is valid then look if we have a reference to an overriden method/constructor
						if (allocationExpr.binding != null && allocationExpr.binding.isValidBinding()) {
							if (methDecl.binding.declaringClass.isCompatibleWith(allocationExpr.resolvedType)) {
								if (allocationExpr.arguments == null && methDecl.arguments == null) {
									superRef = true;
								}
								else if (allocationExpr.arguments != null && methDecl.arguments != null) {
									superRef = methDecl.binding.areParametersCompatibleWith(allocationExpr.binding.parameters);
								}
							}
						}
					}
				}
			}
			catch (Exception e) {
				// Something wrong happen, forget super ref...
			}
		}
		
		// Look at @Override annotations
		if (!superRef && methDecl != null && methDecl.annotations != null) {
			int length = methDecl.annotations.length;
			for (int i=0; i<length && !superRef; i++) {
				superRef = (methDecl.binding.tagBits & TagBits.AnnotationOverride) != 0;
			}
		}
		
		// Store if a reference exists to an overriden method/constructor or the method is in a local type,
		boolean reportMissing = methDecl == null || !((overriding && this.inheritedPositions != -1) || superRef || (methDecl.binding.declaringClass != null && methDecl.binding.declaringClass.isLocalType()));
		if (!overriding && this.inheritedPositions != -1) {
			int start = (int) (this.inheritedPositions >>> 32);
			int end = (int) this.inheritedPositions;
			methScope.problemReporter().javadocUnexpectedTag(start, end);
		}

		// @param tags
		resolveParamTags(methScope, reportMissing);
		resolveTypeParameterTags(methScope, reportMissing);

		// @return tags
		if (this.returnStatement == null) {
			if (reportMissing && methDecl != null) {
				if (methDecl.isMethod()) {
					MethodDeclaration meth = (MethodDeclaration) methDecl;
					if (meth.binding.returnType != TypeBinding.VOID) {
						// method with return should have @return tag
						methScope.problemReporter().javadocMissingReturnTag(meth.returnType.sourceStart, meth.returnType.sourceEnd, methDecl.binding.modifiers);
					}
				}
			}
		} else {
			this.returnStatement.resolve(methScope);
		}

		// @throws/@exception tags
		resolveThrowsTags(methScope, reportMissing);

		// Resolve param tags with invalid syntax
		int length = this.invalidParameters == null ? 0 : this.invalidParameters.length;
		for (int i = 0; i < length; i++) {
			this.invalidParameters[i].resolve(methScope, false);
		}
	}
	
	private void resolveReference(Expression reference, Scope scope) {

		// Perform resolve
		switch (scope.kind) {
			case Scope.METHOD_SCOPE:
				reference.resolveType((MethodScope)scope);
				break;
			case Scope.CLASS_SCOPE:
				reference.resolveType((ClassScope)scope);
				break;
		}

		// Verify field references
		boolean verifyValues = scope.compilerOptions().sourceLevel >= ClassFileConstants.JDK1_5;
		if (reference instanceof JavadocFieldReference) {
			JavadocFieldReference fieldRef = (JavadocFieldReference) reference;
			int modifiers = fieldRef.binding==null ? -1 : fieldRef.binding.modifiers;
			
			// Verify if this is a method reference
			// see bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=51911
			if (fieldRef.methodBinding != null) {
				// cannot refer to method for @value tag
				if (fieldRef.tagValue == JavadocTagConstants.TAG_VALUE_VALUE) {
					scope.problemReporter().javadocInvalidValueReference(fieldRef.sourceStart, fieldRef.sourceEnd, modifiers);
				}
				else if (fieldRef.receiverType != null) {
					fieldRef.superAccess = scope.enclosingSourceType().isCompatibleWith(fieldRef.receiverType);
					fieldRef.methodBinding = scope.findMethod((ReferenceBinding)fieldRef.receiverType, fieldRef.token, new TypeBinding[0], fieldRef);
				}
			}

			// Verify whether field ref should be static or not (for @value tags)
			else if (verifyValues && fieldRef.binding != null && fieldRef.binding.isValidBinding()) {
				if (fieldRef.tagValue == JavadocTagConstants.TAG_VALUE_VALUE && !fieldRef.binding.isStatic()) {
					scope.problemReporter().javadocInvalidValueReference(fieldRef.sourceStart, fieldRef.sourceEnd, modifiers);
				}
			}
			
			// That's it for field references
			return;
		}

		// If not 1.5 level, verification is finished
		if (!verifyValues)  return;

		// Verify that message reference are not used for @value tags
		else if (reference instanceof JavadocMessageSend) {
			JavadocMessageSend msgSend = (JavadocMessageSend) reference;
			int modifiers = msgSend.binding==null ? -1 : msgSend.binding.modifiers;
			if (msgSend.tagValue == JavadocTagConstants.TAG_VALUE_VALUE) { // cannot refer to method for @value tag
				scope.problemReporter().javadocInvalidValueReference(msgSend.sourceStart, msgSend.sourceEnd, modifiers);
			}
		}

		// Verify that constructorreference are not used for @value tags
		else if (reference instanceof JavadocAllocationExpression) {
			JavadocAllocationExpression alloc = (JavadocAllocationExpression) reference;
			int modifiers = alloc.binding==null ? -1 : alloc.binding.modifiers;
			if (alloc.tagValue == JavadocTagConstants.TAG_VALUE_VALUE) { // cannot refer to method for @value tag
				scope.problemReporter().javadocInvalidValueReference(alloc.sourceStart, alloc.sourceEnd, modifiers);
			}
		}
		
		// Verify that there's no type variable reference
		// (javadoc does not accept them and this is not a referenced bug or requested enhancement)
		if (reference.resolvedType != null && reference.resolvedType.isTypeVariable()) {
			scope.problemReporter().javadocInvalidReference(reference.sourceStart, reference.sourceEnd);
		}
	}

	/*
	 * Resolve @param tags while method scope
	 */
	private void resolveParamTags(MethodScope methScope, boolean reportMissing) {
		AbstractMethodDeclaration md = methScope.referenceMethod();
		int paramTagsSize = this.paramReferences == null ? 0 : this.paramReferences.length;

		// If no referenced method (field initializer for example) then report a problem for each param tag
		if (md == null) {
			for (int i = 0; i < paramTagsSize; i++) {
				JavadocSingleNameReference param = this.paramReferences[i];
				methScope.problemReporter().javadocUnexpectedTag(param.tagSourceStart, param.tagSourceEnd);
			}
			return;
		}
		
		// If no param tags then report a problem for each method argument
		int argumentsSize = md.arguments == null ? 0 : md.arguments.length;
		if (paramTagsSize == 0) {
			if (reportMissing) {
				for (int i = 0; i < argumentsSize; i++) {
					Argument arg = md.arguments[i];
					methScope.problemReporter().javadocMissingParamTag(arg.name, arg.sourceStart, arg.sourceEnd, md.binding.modifiers);
				}
			}
		} else {
			LocalVariableBinding[] bindings = new LocalVariableBinding[paramTagsSize];
			int maxBindings = 0;

			// Scan all @param tags
			for (int i = 0; i < paramTagsSize; i++) {
				JavadocSingleNameReference param = this.paramReferences[i];
				param.resolve(methScope);
				if (param.binding != null && param.binding.isValidBinding()) {
					// Verify duplicated tags
					boolean found = false;
					for (int j = 0; j < maxBindings && !found; j++) {
						if (bindings[j] == param.binding) {
							methScope.problemReporter().javadocDuplicatedParamTag(param.token, param.sourceStart, param.sourceEnd, md.binding.modifiers);
							found = true;
						}
					}
					if (!found) {
						bindings[maxBindings++] = (LocalVariableBinding) param.binding;
					}
				}
			}

			// Look for undocumented arguments
			if (reportMissing) {
				for (int i = 0; i < argumentsSize; i++) {
					Argument arg = md.arguments[i];
					boolean found = false;
					for (int j = 0; j < maxBindings && !found; j++) {
						LocalVariableBinding binding = bindings[j];
						if (arg.binding == binding) {
							found = true;
						}
					}
					if (!found) {
						methScope.problemReporter().javadocMissingParamTag(arg.name, arg.sourceStart, arg.sourceEnd, md.binding.modifiers);
					}
				}
			}
		}
	}

	/*
	 * Resolve @param tags for type parameters
	 */
	private void resolveTypeParameterTags(Scope scope, boolean reportMissing) {
		int paramTypeParamLength = this.paramTypeParameters == null ? 0 : this.paramTypeParameters.length;

		// Get declaration infos
		TypeParameter[] parameters = null;
		TypeVariableBinding[] typeVariables = null;
		int modifiers = -1;
		switch (scope.kind) {
			case Scope.METHOD_SCOPE:
				AbstractMethodDeclaration methodDeclaration = ((MethodScope)scope).referenceMethod();
				// If no referenced method (field initializer for example) then report a problem for each param tag
				if (methodDeclaration == null) {
					for (int i = 0; i < paramTypeParamLength; i++) {
						JavadocSingleNameReference param = this.paramReferences[i];
						scope.problemReporter().javadocUnexpectedTag(param.tagSourceStart, param.tagSourceEnd);
					}
					return;
				}
				parameters = methodDeclaration.typeParameters();
				typeVariables = methodDeclaration.binding.typeVariables;
				modifiers = methodDeclaration.binding.modifiers;
				break;
			case Scope.CLASS_SCOPE:
				TypeDeclaration typeDeclaration = ((ClassScope) scope).referenceContext;
				parameters = typeDeclaration.typeParameters;
				typeVariables = typeDeclaration.binding.typeVariables;
				modifiers = typeDeclaration.binding.modifiers;
				break;
		}

		// If no type variables then report a problem for each param type parameter tag
		if (typeVariables == null || typeVariables.length == 0) {
			for (int i = 0; i < paramTypeParamLength; i++) {
				JavadocSingleTypeReference param = this.paramTypeParameters[i];
				scope.problemReporter().javadocUnexpectedTag(param.tagSourceStart, param.tagSourceEnd);
			}
			return;
		}
		
		// If no param tags then report a problem for each declaration type parameter
		if (parameters != null) {
			int typeParametersLength = parameters.length;
			if (paramTypeParamLength == 0) {
				if (reportMissing) {
					for (int i = 0, l=typeParametersLength; i<l; i++) {
						scope.problemReporter().javadocMissingParamTag(parameters[i].name, parameters[i].sourceStart, parameters[i].sourceEnd, modifiers);
					}
				}

			// Otherwise verify that all param tags match type parameters
			} else if (typeVariables.length == typeParametersLength) {
				TypeVariableBinding[] bindings = new TypeVariableBinding[paramTypeParamLength];

				// Scan all @param tags
				for (int i = 0; i < paramTypeParamLength; i++) {
					JavadocSingleTypeReference param = this.paramTypeParameters[i];
					TypeBinding paramBindind = param.internalResolveType(scope);
					if (paramBindind != null && paramBindind.isValidBinding()) {
						if (paramBindind.isTypeVariable()) {
							// Verify duplicated tags
							boolean duplicate = false;
							for (int j = 0; j < i && !duplicate; j++) {
								if (bindings[j] == param.resolvedType) {
									scope.problemReporter().javadocDuplicatedParamTag(param.token, param.sourceStart, param.sourceEnd, modifiers);
									duplicate = true;
								}
							}
							if (!duplicate) {
								bindings[i] = (TypeVariableBinding) param.resolvedType;
							}
						} else {
							scope.problemReporter().javadocUndeclaredParamTagName(param.token, param.sourceStart, param.sourceEnd, modifiers);
						}
					}
				}

				// Look for undocumented type parameters
				for (int i = 0; i < typeParametersLength; i++) {
					TypeParameter parameter = parameters[i];
					boolean found = false;
					for (int j = 0; j < paramTypeParamLength && !found; j++) {
						if (parameter.binding == bindings[j]) {
							found = true;
							bindings[j] = null;
						}
					}
					if (!found && reportMissing) {
						scope.problemReporter().javadocMissingParamTag(parameter.name, parameter.sourceStart, parameter.sourceEnd, modifiers);
					}
				}
			
				// Report invalid param
				for (int i=0; i<paramTypeParamLength; i++) {
					if (bindings[i] != null) {
						JavadocSingleTypeReference param = this.paramTypeParameters[i];
						scope.problemReporter().javadocUndeclaredParamTagName(param.token, param.sourceStart, param.sourceEnd, modifiers);
					}
				}
			}
		}
	}

	/*
	 * Resolve @throws/@exception tags while method scope
	 */
	private void resolveThrowsTags(MethodScope methScope, boolean reportMissing) {
		AbstractMethodDeclaration md = methScope.referenceMethod();
		int throwsTagsLength = this.exceptionReferences == null ? 0 : this.exceptionReferences.length;

		// If no referenced method (field initializer for example) then report a problem for each throws tag
		if (md == null) {
			for (int i = 0; i < throwsTagsLength; i++) {
				TypeReference typeRef = this.exceptionReferences[i];
				int start = typeRef.sourceStart;
				int end = typeRef.sourceEnd;
				if (typeRef instanceof JavadocQualifiedTypeReference) {
					start = ((JavadocQualifiedTypeReference) typeRef).tagSourceStart;
					end = ((JavadocQualifiedTypeReference) typeRef).tagSourceEnd;
				} else if (typeRef instanceof JavadocSingleTypeReference) {
					start = ((JavadocSingleTypeReference) typeRef).tagSourceStart;
					end = ((JavadocSingleTypeReference) typeRef).tagSourceEnd;
				}
				methScope.problemReporter().javadocUnexpectedTag(start, end);
			}
			return;
		}

		// If no throws tags then report a problem for each method thrown exception
		int boundExceptionLength = (md.binding == null || md.binding.thrownExceptions == null) ? 0 : md.binding.thrownExceptions.length;
		int thrownExceptionLength = md.thrownExceptions == null ? 0 : md.thrownExceptions.length;
		if (throwsTagsLength == 0) {
			if (reportMissing) {
				for (int i = 0; i < boundExceptionLength; i++) {
					ReferenceBinding exceptionBinding = md.binding.thrownExceptions[i];
					if (exceptionBinding != null && exceptionBinding.isValidBinding()) { // flag only valid class name
						int j=i;
						while (j<thrownExceptionLength && exceptionBinding != md.thrownExceptions[j].resolvedType) j++;
						if (j<thrownExceptionLength) {
							methScope.problemReporter().javadocMissingThrowsTag(md.thrownExceptions[j], md.binding.modifiers);
						}
					}
				}
			}
		} else {
			int maxRef = 0;
			TypeReference[] typeReferences = new TypeReference[throwsTagsLength];

			// Scan all @throws tags
			for (int i = 0; i < throwsTagsLength; i++) {
				TypeReference typeRef = this.exceptionReferences[i];
				typeRef.resolve(methScope);
				TypeBinding typeBinding = typeRef.resolvedType;

				if (typeBinding != null && typeBinding.isValidBinding() && typeBinding.isClass()) {
					// accept only valid class binding
					typeReferences[maxRef++] = typeRef;
				}
			}

			// Look for undocumented thrown exception
			for (int i = 0; i < boundExceptionLength; i++) {
				ReferenceBinding exceptionBinding = md.binding.thrownExceptions[i];
				if (exceptionBinding != null) exceptionBinding = (ReferenceBinding) exceptionBinding.erasure();
				boolean found = false;
				for (int j = 0; j < maxRef && !found; j++) {
					if (typeReferences[j] != null) {
						TypeBinding typeBinding = typeReferences[j].resolvedType;
						if (exceptionBinding == typeBinding) {
							found = true;
							typeReferences[j] = null;
						}
					}
				}
				if (!found && reportMissing) {
					if (exceptionBinding != null && exceptionBinding.isValidBinding()) { // flag only valid class name
						int k=i;
						while (k<thrownExceptionLength && exceptionBinding != md.thrownExceptions[k].resolvedType) k++;
						if (k<thrownExceptionLength) {
							methScope.problemReporter().javadocMissingThrowsTag(md.thrownExceptions[k], md.binding.modifiers);
						}
					}
				}
			}

			// Verify additional @throws tags
			for (int i = 0; i < maxRef; i++) {
				TypeReference typeRef = typeReferences[i];
				if (typeRef != null) {
					boolean compatible = false;
					// thrown exceptions subclasses are accepted
					for (int j = 0; j<thrownExceptionLength && !compatible; j++) {
						TypeBinding exceptionBinding = md.thrownExceptions[j].resolvedType;
						if (exceptionBinding != null) {
							compatible = typeRef.resolvedType.isCompatibleWith(exceptionBinding);
						}
					}
			
					//  If not compatible only complain on unchecked exception
					if (!compatible && !typeRef.resolvedType.isUncheckedException(false)) {
						methScope.problemReporter().javadocInvalidThrowsClassName(typeRef, md.binding.modifiers);
					}
				}
			}
		}
	}
	
	/*
	 * Search node with a given staring position in javadoc objects arrays.
	 */
	public ASTNode getNodeStartingAt(int start) {
		int length = 0;
		// parameters array
		if (this.paramReferences != null) {
			length = this.paramReferences.length;
			for (int i=0; i<length; i++) {
				JavadocSingleNameReference param = this.paramReferences[i];
				if (param.sourceStart==start) {
					return param;
				}
			}
		}
		// array of invalid syntax tags parameters
		if (this.invalidParameters != null) {
			length = this.invalidParameters.length;
			for (int i=0; i<length; i++) {
				JavadocSingleNameReference param = this.invalidParameters[i];
				if (param.sourceStart==start) {
					return param;
				}
			}
		}
		// type parameters array
		if (this.paramTypeParameters != null) {
			length = this.paramTypeParameters.length;
			for (int i=0; i<length; i++) {
				JavadocSingleTypeReference param = this.paramTypeParameters[i];
				if (param.sourceStart==start) {
					return param;
				}
			}
		}
		// thrown exception array
		if (this.exceptionReferences != null) {
			length = this.exceptionReferences.length;
			for (int i=0; i<length; i++) {
				TypeReference typeRef = this.exceptionReferences[i];
				if (typeRef.sourceStart==start) {
					return typeRef;
				}
			}
		}
		// references array
		if (this.seeReferences != null) {
			length = this.seeReferences.length;
			for (int i=0; i<length; i++) {
				org.eclipse.jdt.internal.compiler.ast.Expression expression = this.seeReferences[i];
				if (expression.sourceStart==start) {
					return expression;
				} else if (expression instanceof JavadocAllocationExpression) {
					JavadocAllocationExpression allocationExpr = (JavadocAllocationExpression) this.seeReferences[i];
					// if binding is valid then look at arguments
					if (allocationExpr.binding != null && allocationExpr.binding.isValidBinding()) {
						if (allocationExpr.arguments != null) {
							for (int j=0, l=allocationExpr.arguments.length; j<l; j++) {
								if (allocationExpr.arguments[j].sourceStart == start) {
									return allocationExpr.arguments[j];
								}
							}
						}
					}
				} else if (expression instanceof JavadocMessageSend) {
					JavadocMessageSend messageSend = (JavadocMessageSend) this.seeReferences[i];
					// if binding is valid then look at arguments
					if (messageSend.binding != null && messageSend.binding.isValidBinding()) {
						if (messageSend.arguments != null) {
							for (int j=0, l=messageSend.arguments.length; j<l; j++) {
								if (messageSend.arguments[j].sourceStart == start) {
									return messageSend.arguments[j];
								}
							}
						}
					}
				}
			}
		}
		return null;
	}
}
