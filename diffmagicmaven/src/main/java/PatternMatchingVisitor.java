import org.eclipse.jdt.core.dom.*;
import java.util.List;

public class PatternMatchingVisitor extends ASTVisitor {
    private SimpleName usageNode;
    private int usageIdNum;
    private List<Main.Edge> nameResEdges;
    private EdgeAdder edgeAdder;
    
    public PatternMatchingVisitor(SimpleName usageNode, List<Main.Edge> nameResEdges, EdgeAdder edgeAdder) {
        this.usageNode = usageNode;
        this.usageIdNum = usageNode.hashCode();
        this.nameResEdges = nameResEdges;
        this.edgeAdder = edgeAdder;
    }
    
    @Override
    public boolean visit(MethodDeclaration node) {
        // Check if usageNode is the method name in a method declaration
        if (node.getName() == usageNode) {
            // Check if this is a constructor declaration
            if (node.isConstructor()) {
                handleConstructorDeclaration(node);
            } else {
                handleMethodDeclaration(node);
            }
            return false;
        }
        return true;
    }
    
    @Override
    public boolean visit(Assignment assignment) {
        // Check if the usageNode is involved in this assignment
        Expression leftSide = assignment.getLeftHandSide();
        Expression rightSide = assignment.getRightHandSide();
        
        // Handle simple assignments where usageNode is on the left
        if (leftSide == usageNode || containsNode(leftSide, usageNode)) {
            handleAssignmentLeftSide(assignment, rightSide);
            return false;
        }
        
        // Handle assignments where usageNode is on the right
        if (rightSide == usageNode || containsNode(rightSide, usageNode)) {
            handleAssignmentRightSide(assignment, leftSide);
            return false;
        }
        
        return true;
    }
    
    @Override
    public boolean visit(MethodInvocation methodInvocation) {
        // Check if this method invocation is being assigned to a variable
        ASTNode parent = methodInvocation.getParent();
        
        if (parent instanceof Assignment) {
            Assignment assignment = (Assignment) parent;
            if (assignment.getRightHandSide() == methodInvocation) {
                handleMethodInvocationAssignment(methodInvocation, assignment.getLeftHandSide());
            }
        } else if (parent instanceof VariableDeclarationFragment) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) parent;
            if (fragment.getInitializer() == methodInvocation) {
                handleMethodInvocationInitialization(methodInvocation, fragment);
            }
        }
        
        // Check if this method invocation is the expression in a cast
        if (parent instanceof CastExpression && methodInvocation.getName() == usageNode) {
            handleCastMethodInvocation((CastExpression) parent, methodInvocation);
        }
        
        return true;
    }
    
    @Override
    public boolean visit(VariableDeclarationFragment fragment) {
        // Add edge from fragment to its parent declaration statement
        if (fragment.getName() == usageNode) {
            handleFragmentToDeclarationEdge(fragment);
        }

        // Check if this is a variable declaration with initialization
        if (fragment.getName() == usageNode && fragment.getInitializer() != null) {
            handleVariableInitialization(fragment);
            return false;
        } else if (fragment.getInitializer() != null && 
                   (fragment.getInitializer() == usageNode || containsNode(fragment.getInitializer(), usageNode))) {
            handleInitializerExpression(fragment);
            return false;
        }
        
        return true;
    }
    
    @Override
    public boolean visit(FieldAccess fieldAccess) {
        // Check if usageNode is the field name in a field access
        if (fieldAccess.getName() == usageNode) {
            // Check if this FieldAccess is on the left side of an assignment with a literal
            ASTNode parent = fieldAccess.getParent();
            if (parent instanceof Assignment) {
                Assignment assignment = (Assignment) parent;
                if (assignment.getLeftHandSide() == fieldAccess && !(assignment.getRightHandSide() instanceof SimpleName)) {
                    handleFieldAccessLiteralAssignment(fieldAccess, assignment.getRightHandSide());
                }
            }
        }
        return true;
    }
    
    @Override
    public boolean visit(CastExpression node) {
        // Check if usageNode is the expression being cast (must be a variable)
        Expression expression = node.getExpression();
        if (expression == usageNode) {
            handleCastVariable(node);
            return false;
        }
        return true;
    }
    
    @Override
    public boolean visit(ReturnStatement returnStatement) {
        // Check if the usageNode is in the return expression
        Expression returnExpression = returnStatement.getExpression();
        if (returnExpression == usageNode || containsNode(returnExpression, usageNode)) {
            handleReturnVariable(returnStatement);
            return false;
        }
        return true;
    }
    
    @Override
    public boolean visit(SimpleType node) {
        System.out.println("  Pattern: Parametric edge");
        // Check if usageNode is the name of this SimpleType
        Name typeName = node.getName();
        SimpleName simpleName = null;
        
        if (typeName instanceof SimpleName) {
            simpleName = (SimpleName) typeName;
        } else if (typeName instanceof QualifiedName) {
            simpleName = ((QualifiedName) typeName).getName();
        }
        
        if (simpleName == usageNode) {
            // Check if this SimpleType is a type argument in a ParameterizedType
            ASTNode parent = node.getParent();
            if (parent instanceof ParameterizedType) {
                handleTypeArgument(node, (ParameterizedType) parent);
                return false;
            }
        }
        return true;
    }
    
    private boolean containsNode(ASTNode parent, ASTNode target) {
        if (parent == null || target == null) {
            return false;
        }
        if (parent == target) {
            return true;
        }
        
        // Check if target is a descendant of parent
        final boolean[] found = new boolean[1];
        parent.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node == target) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }
    
    private void handleReturnVariable(ReturnStatement returnStatement) {
        System.out.println("  Pattern: SimpleName is a return variable");
        
        // Find the enclosing method
        ASTNode parent = returnStatement.getParent();
        while (parent != null && !(parent instanceof MethodDeclaration)) {
            parent = parent.getParent();
        }
        
        if (parent instanceof MethodDeclaration) {
            MethodDeclaration methodDecl = (MethodDeclaration) parent;
            Type returnType = methodDecl.getReturnType2();
            
            if (returnType == null || (returnType.isPrimitiveType() && 
                ((PrimitiveType) returnType).getPrimitiveTypeCode() == PrimitiveType.VOID)) {
                return;
            }
            
            IBinding binding = usageNode.resolveBinding();
            if (binding instanceof IVariableBinding) {
                IVariableBinding varBinding = (IVariableBinding) binding;
                ASTNode varDeclaration = edgeAdder.findDeclaringNodeForBinding(varBinding);
                
                Type varType = edgeAdder.getTypeFromDeclaration(varDeclaration);
                
                if (varType != null) {
                    String methodReturnTypeId = "ReturnVarType:MethodReturnType:" + returnType.toString() + "@" + returnType.getStartPosition();
                    int methodReturnTypeIdNum = returnType.hashCode();
                    
                    String varTypeId = "ReturnVarType:VarType:" + varType.toString() + "@" + varType.getStartPosition();
                    int varTypeIdNum = varType.hashCode();
                    
                    nameResEdges.add(new Main.Edge(varTypeId, methodReturnTypeId, varTypeIdNum, methodReturnTypeIdNum));
                    
                    System.out.println("  Added variable-type-to-method-return-type edge: " + varTypeId + " -> " + methodReturnTypeId);
                } else {
                    System.out.println("  WARNING: Could not find type for variable: " + usageNode.getIdentifier());
                }
            }
        }
    }
    
    private void handleConstructorDeclaration(MethodDeclaration constructor) {
        System.out.println("  Pattern: SimpleName is a constructor declaration");
        
        // Find the enclosing type declaration
        ASTNode parent = constructor.getParent();
        while (parent != null && !(parent instanceof TypeDeclaration) && !(parent instanceof EnumDeclaration)) {
            parent = parent.getParent();
        }
        
        if (parent instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) parent;
            SimpleName className = typeDecl.getName();
            
            String constructorId = "ConstructorToClass:SimpleName:" + usageNode.toString() + "@" + usageNode.getStartPosition();
            String classNameId = "ConstructorToClass:ClassName:" + className.toString() + "@" + className.getStartPosition();
            int classNameIdNum = className.hashCode();
            
            nameResEdges.add(new Main.Edge(constructorId, classNameId, usageIdNum, classNameIdNum));
            
            System.out.println("  Added constructor-declaration-to-class edge: " + usageNode.getIdentifier() + " -> " + className.getIdentifier());
        } else if (parent instanceof EnumDeclaration) {
            EnumDeclaration enumDecl = (EnumDeclaration) parent;
            SimpleName enumName = enumDecl.getName();
            
            String constructorId = "ConstructorToEnum:SimpleName:" + usageNode.toString() + "@" + usageNode.getStartPosition();
            String enumNameId = "ConstructorToEnum:EnumName:" + enumName.toString() + "@" + enumName.getStartPosition();
            int enumNameIdNum = enumName.hashCode();
            
            nameResEdges.add(new Main.Edge(constructorId, enumNameId, usageIdNum, enumNameIdNum));
            
            System.out.println("  Added constructor-declaration-to-enum edge: " + usageNode.getIdentifier() + " -> " + enumName.getIdentifier());
        }
    }
    
    private void handleMethodDeclaration(MethodDeclaration node) {
        IMethodBinding methodBinding = node.resolveBinding();
        if (methodBinding == null) {
            return;
        }
        
        System.out.println("  Pattern: SimpleName is a method declaration");
        
        // Check if this method overrides/implements a method from a superclass/interface
        IMethodBinding originalMethod = findOriginalMethod(methodBinding);
        
        if (originalMethod != null && !originalMethod.equals(methodBinding)) {
            ASTNode originalMethodDecl = edgeAdder.findDeclaringNodeForBinding(originalMethod);
            
            if (originalMethodDecl instanceof MethodDeclaration) {
                MethodDeclaration originalDecl = (MethodDeclaration) originalMethodDecl;
                SimpleName originalName = originalDecl.getName();
                
                String overridingId = "OverrideMethod:SimpleName:" + usageNode.toString() + "@" + usageNode.getStartPosition();
                String originalNameId = "OverrideMethod:OriginalMethod:" + originalName.toString() + "@" + originalName.getStartPosition();
                int originalNameIdNum = originalName.hashCode();
                
                nameResEdges.add(new Main.Edge(overridingId, originalNameId, usageIdNum, originalNameIdNum));
                
                System.out.println("  Added overriding-method-to-original edge: " + usageNode.getIdentifier() + " -> " + originalName.getIdentifier());
            }
        }
    }
    
    private IMethodBinding findOriginalMethod(IMethodBinding methodBinding) {
        ITypeBinding declaringClass = methodBinding.getDeclaringClass();
        if (declaringClass == null) {
            return null;
        }
        
        // Check superclass
        ITypeBinding superclass = declaringClass.getSuperclass();
        if (superclass != null) {
            for (IMethodBinding superMethod : superclass.getDeclaredMethods()) {
                if (methodBinding.overrides(superMethod)) {
                    // Recursively find the original method
                    IMethodBinding original = findOriginalMethod(superMethod);
                    return original != null ? original : superMethod;
                }
            }
        }
        
        // Check interfaces
        for (ITypeBinding interfaceType : declaringClass.getInterfaces()) {
            for (IMethodBinding interfaceMethod : interfaceType.getDeclaredMethods()) {
                if (methodBinding.overrides(interfaceMethod)) {
                    // Recursively find the original method
                    IMethodBinding original = findOriginalMethod(interfaceMethod);
                    return original != null ? original : interfaceMethod;
                }
            }
        }
        
        return null;
    }
    
    private void handleAssignmentLeftSide(Assignment assignment, Expression rightSide) {
        System.out.println("  Pattern: SimpleName is on left side of assignment");
        
        IBinding binding = usageNode.resolveBinding();
        if (binding instanceof IVariableBinding) {
            IVariableBinding varBinding = (IVariableBinding) binding;
            ASTNode varDeclaration = edgeAdder.findDeclaringNodeForBinding(varBinding);
            Type leftType = edgeAdder.getTypeFromDeclaration(varDeclaration);
            
            if (leftType != null) {
                if (rightSide instanceof SimpleName) {
                    // Right side is a variable - link the two variable types
                    SimpleName rightVar = (SimpleName) rightSide;
                    IBinding rightBinding = rightVar.resolveBinding();
                    
                    if (rightBinding instanceof IVariableBinding) {
                        IVariableBinding rightVarBinding = (IVariableBinding) rightBinding;
                        ASTNode rightVarDeclaration = edgeAdder.findDeclaringNodeForBinding(rightVarBinding);
                        Type rightType = edgeAdder.getTypeFromDeclaration(rightVarDeclaration);
                        
                        if (rightType != null) {
                            String rightTypeId = "Assignment:RightVarType:" + rightType.toString() + "@" + rightType.getStartPosition();
                            int rightTypeIdNum = rightType.hashCode();
                            
                            String leftTypeId = "Assignment:LeftVarType:" + leftType.toString() + "@" + leftType.getStartPosition();
                            int leftTypeIdNum = leftType.hashCode();
                            
                            nameResEdges.add(new Main.Edge(rightTypeId, leftTypeId, rightTypeIdNum, leftTypeIdNum));
                            
                            System.out.println("  Added assignment variable-type edge: " + rightType.toString() + " -> " + leftType.toString());
                        }
                    }
                } else {
                    // Right side is a literal or expression - link literal to variable type
                    String rightLiteralId = "Assignment:RightLiteral:" + rightSide.getClass().getSimpleName() + "@" + rightSide.getStartPosition();
                    int rightLiteralIdNum = rightSide.hashCode();
                    
                    String leftTypeId = "Assignment:LeftVarType:" + leftType.toString() + "@" + leftType.getStartPosition();
                    int leftTypeIdNum = leftType.hashCode();
                    
                    nameResEdges.add(new Main.Edge(rightLiteralId, leftTypeId, rightLiteralIdNum, leftTypeIdNum));
                    
                    System.out.println("  Added assignment literal-to-type edge: " + rightSide.getClass().getSimpleName() + " -> " + leftType.toString());
                }
            }
        }
    }
    
    private void handleAssignmentRightSide(Assignment assignment, Expression leftSide) {
        System.out.println("  Pattern: SimpleName is on right side of assignment");
        
        // Get the type of the right side (usageNode)
        IBinding rightBinding = usageNode.resolveBinding();
        if (!(rightBinding instanceof IVariableBinding)) {
            return;
        }
        
        IVariableBinding rightVarBinding = (IVariableBinding) rightBinding;
        ASTNode rightVarDeclaration = edgeAdder.findDeclaringNodeForBinding(rightVarBinding);
        Type rightType = edgeAdder.getTypeFromDeclaration(rightVarDeclaration);
        
        if (rightType == null) {
            return;
        }
        
        // Get the type and name of the left side
        SimpleName leftFieldName = null;
        boolean isFieldAssignment = false;
        
        if (leftSide instanceof FieldAccess) {
            leftFieldName = ((FieldAccess) leftSide).getName();
            isFieldAssignment = true;
        } else if (leftSide instanceof QualifiedName) {
            leftFieldName = ((QualifiedName) leftSide).getName();
            isFieldAssignment = true;
        } else if (leftSide instanceof SimpleName) {
            leftFieldName = (SimpleName) leftSide;
        }
        
        if (leftFieldName == null) {
            return;
        }
        
        IBinding leftBinding = leftFieldName.resolveBinding();
        if (!(leftBinding instanceof IVariableBinding)) {
            return;
        }
        
        IVariableBinding leftVarBinding = (IVariableBinding) leftBinding;
        ASTNode leftVarDeclaration = edgeAdder.findDeclaringNodeForBinding(leftVarBinding);
        Type leftType = edgeAdder.getTypeFromDeclaration(leftVarDeclaration);
        
        if (leftType != null) {
            String prefix = isFieldAssignment ? "FieldAssignment:" : "Assignment:";
            String rightTypeId = prefix + "RightVarType:" + rightType.toString() + "@" + rightType.getStartPosition();
            int rightTypeIdNum = rightType.hashCode();
            
            String leftTypeId = prefix + (isFieldAssignment ? "LeftFieldType:" : "LeftVarType:") + leftType.toString() + "@" + leftType.getStartPosition();
            int leftTypeIdNum = leftType.hashCode();
            
            nameResEdges.add(new Main.Edge(rightTypeId, leftTypeId, rightTypeIdNum, leftTypeIdNum));
            
            System.out.println("  Added " + (isFieldAssignment ? "field-" : "") + "assignment variable-type edge: " + rightType.toString() + " -> " + leftType.toString());
        }
    }
    
    private void handleVariableInitialization(VariableDeclarationFragment fragment) {
        System.out.println("  Pattern: SimpleName is a variable being initialized");
        
        Expression initializer = fragment.getInitializer();
        
        // Get the type of the variable being declared
        ASTNode parent = fragment.getParent();
        Type varType = null;
        
        if (parent instanceof VariableDeclarationStatement) {
            varType = ((VariableDeclarationStatement) parent).getType();
        } else if (parent instanceof VariableDeclarationExpression) {
            varType = ((VariableDeclarationExpression) parent).getType();
        } else if (parent instanceof FieldDeclaration) {
            varType = ((FieldDeclaration) parent).getType();
        }
        
        if (varType != null) {
            if (initializer instanceof SimpleName) {
                // Initializer is a variable - link the two variable types
                SimpleName initVar = (SimpleName) initializer;
                IBinding initBinding = initVar.resolveBinding();
                
                if (initBinding instanceof IVariableBinding) {
                    IVariableBinding initVarBinding = (IVariableBinding) initBinding;
                    ASTNode initVarDeclaration = edgeAdder.findDeclaringNodeForBinding(initVarBinding);
                    Type initType = edgeAdder.getTypeFromDeclaration(initVarDeclaration);
                    
                    if (initType != null) {
                        String initTypeId = "VarInit:InitVarType:" + initType.toString() + "@" + initType.getStartPosition();
                        int initTypeIdNum = initType.hashCode();
                        
                        String varTypeId = "VarInit:DeclVarType:" + varType.toString() + "@" + varType.getStartPosition();
                        int varTypeIdNum = varType.hashCode();
                        
                        nameResEdges.add(new Main.Edge(initTypeId, varTypeId, initTypeIdNum, varTypeIdNum));
                        
                        System.out.println("  Added initialization variable-type edge: " + initType.toString() + " -> " + varType.toString());
                    }
                }
            } else {
                // Initializer is a literal or expression - link literal to variable type
                String initLiteralId = "VarInit:InitLiteral:" + initializer.getClass().getSimpleName() + "@" + initializer.getStartPosition();
                int initLiteralIdNum = initializer.hashCode();
                
                String varTypeId = "VarInit:DeclVarType:" + varType.toString() + "@" + varType.getStartPosition();
                int varTypeIdNum = varType.hashCode();
                
                nameResEdges.add(new Main.Edge(initLiteralId, varTypeId, initLiteralIdNum, varTypeIdNum));
                
                System.out.println("  Added initialization literal-to-type edge: " + initializer.getClass().getSimpleName() + " -> " + varType.toString());
            }
        }
    }
    
    private void handleFieldAccessLiteralAssignment(FieldAccess fieldAccess, Expression rightSide) {
        System.out.println("  Pattern: SimpleName is a field in a literal assignment");
        
        IBinding fieldBinding = usageNode.resolveBinding();
        if (!(fieldBinding instanceof IVariableBinding)) {
            return;
        }
        
        IVariableBinding varBinding = (IVariableBinding) fieldBinding;
        ASTNode fieldDeclaration = edgeAdder.findDeclaringNodeForBinding(varBinding);
        Type fieldType = edgeAdder.getTypeFromDeclaration(fieldDeclaration);
        
        if (fieldType != null) {
            String rightLiteralId = "FieldAssignment:RightLiteral:" + rightSide.getClass().getSimpleName() + "@" + rightSide.getStartPosition();
            int rightLiteralIdNum = rightSide.hashCode();
            
            String leftTypeId = "FieldAssignment:LeftFieldType:" + fieldType.toString() + "@" + fieldType.getStartPosition();
            int leftTypeIdNum = fieldType.hashCode();
            
            nameResEdges.add(new Main.Edge(rightLiteralId, leftTypeId, rightLiteralIdNum, leftTypeIdNum));
            
            System.out.println("  Added field-assignment literal-to-type edge: " + rightSide.getClass().getSimpleName() + " -> " + fieldType.toString());
        }
    }
    
    private void handleInitializerExpression(VariableDeclarationFragment fragment) {
        System.out.println("  Pattern: SimpleName is in an initializer expression");
        
        // This handles when usageNode is on the right side of a variable initialization
        IBinding binding = usageNode.resolveBinding();
        if (binding instanceof IVariableBinding) {
            IVariableBinding varBinding = (IVariableBinding) binding;
            ASTNode varDeclaration = edgeAdder.findDeclaringNodeForBinding(varBinding);
            Type initType = edgeAdder.getTypeFromDeclaration(varDeclaration);
            
            // Get the type of the variable being declared
            ASTNode parent = fragment.getParent();
            Type varType = null;
            
            if (parent instanceof VariableDeclarationStatement) {
                varType = ((VariableDeclarationStatement) parent).getType();
            } else if (parent instanceof VariableDeclarationExpression) {
                varType = ((VariableDeclarationExpression) parent).getType();
            } else if (parent instanceof FieldDeclaration) {
                varType = ((FieldDeclaration) parent).getType();
            }
            
            if (initType != null && varType != null) {
                String initTypeId = "VarInit:InitVarType:" + initType.toString() + "@" + initType.getStartPosition();
                int initTypeIdNum = initType.hashCode();
                
                String varTypeId = "VarInit:DeclVarType:" + varType.toString() + "@" + varType.getStartPosition();
                int varTypeIdNum = varType.hashCode();
                
                nameResEdges.add(new Main.Edge(initTypeId, varTypeId, initTypeIdNum, varTypeIdNum));
                
                System.out.println("  Added initialization variable-type edge: " + initType.toString() + " -> " + varType.toString());
            }
        }
    }

    private void handleFragmentToDeclarationEdge(VariableDeclarationFragment fragment) {
        ASTNode parent = fragment.getParent();
        
        if (parent instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement statement = (VariableDeclarationStatement) parent;
            
            String fragmentId = "FragmentToStmt:Fragment:" + fragment.getName().getIdentifier() + "@" + fragment.getStartPosition();
            int fragmentIdNum = fragment.hashCode();
            
            String stmtId = "FragmentToStmt:Statement:" + statement.getClass().getSimpleName() + "@" + statement.getStartPosition();
            int stmtIdNum = statement.hashCode();
            
            nameResEdges.add(new Main.Edge(fragmentId, stmtId, fragmentIdNum, stmtIdNum));
            
            System.out.println("  Added fragment-to-statement edge: " + fragment.getName().getIdentifier() + "@" + fragment.getStartPosition() + " -> VariableDeclarationStatement@" + statement.getStartPosition());
        } else if (parent instanceof VariableDeclarationExpression) {
            VariableDeclarationExpression expression = (VariableDeclarationExpression) parent;
            
            String fragmentId = "FragmentToExpr:Fragment:" + fragment.getName().getIdentifier() + "@" + fragment.getStartPosition();
            int fragmentIdNum = fragment.hashCode();
            
            String exprId = "FragmentToExpr:Expression:" + expression.getClass().getSimpleName() + "@" + expression.getStartPosition();
            int exprIdNum = expression.hashCode();
            
            nameResEdges.add(new Main.Edge(fragmentId, exprId, fragmentIdNum, exprIdNum));
            
            System.out.println("  Added fragment-to-expression edge: " + fragment.getName().getIdentifier() + "@" + fragment.getStartPosition() + " -> VariableDeclarationExpression@" + expression.getStartPosition());
        } else if (parent instanceof FieldDeclaration) {
            FieldDeclaration field = (FieldDeclaration) parent;
            
            String fragmentId = "FragmentToField:Fragment:" + fragment.getName().getIdentifier() + "@" + fragment.getStartPosition();
            int fragmentIdNum = fragment.hashCode();
            
            String fieldId = "FragmentToField:Field:" + field.getClass().getSimpleName() + "@" + field.getStartPosition();
            int fieldIdNum = field.hashCode();
            
            nameResEdges.add(new Main.Edge(fragmentId, fieldId, fragmentIdNum, fieldIdNum));
            
            System.out.println("  Added fragment-to-field edge: " + fragment.getName().getIdentifier() + "@" + fragment.getStartPosition() + " -> FieldDeclaration@" + field.getStartPosition());
        }
    }

    private void handleMethodInvocationAssignment(MethodInvocation methodInvocation, Expression leftSide) {
        System.out.println("  Pattern: MethodInvocation assigned to variable");
        
        // Get the method binding to find the return type
        IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
        if (methodBinding == null) {
            System.out.println("  WARNING: Could not resolve method binding");
            return;
        }
        
        ITypeBinding returnTypeBinding = methodBinding.getReturnType();
        if (returnTypeBinding == null || returnTypeBinding.getName().equals("void")) {
            return;
        }
        
        // Find the method declaration to get the return type AST node
        ASTNode methodDecl = edgeAdder.findDeclaringNodeForBinding(methodBinding);
        if (!(methodDecl instanceof MethodDeclaration)) {
            System.out.println("  WARNING: Could not find method declaration");
            return;
        }
        
        Type returnType = ((MethodDeclaration) methodDecl).getReturnType2();
        if (returnType == null) {
            return;
        }
        
        // Get the variable type from the left side
        SimpleName varName = null;
        if (leftSide instanceof SimpleName) {
            varName = (SimpleName) leftSide;
        } else if (leftSide instanceof FieldAccess) {
            varName = ((FieldAccess) leftSide).getName();
        } else if (leftSide instanceof QualifiedName) {
            varName = ((QualifiedName) leftSide).getName();
        }
        
        if (varName == null) {
            return;
        }
        
        IBinding varBinding = varName.resolveBinding();
        if (!(varBinding instanceof IVariableBinding)) {
            return;
        }
        
        ASTNode varDecl = edgeAdder.findDeclaringNodeForBinding(varBinding);
        Type varType = edgeAdder.getTypeFromDeclaration(varDecl);
        
        if (varType != null) {
            String returnTypeId = "MethodInvocAssign:ReturnType:" + returnType.toString() + "@" + returnType.getStartPosition();
            int returnTypeIdNum = returnType.hashCode();
            
            String varTypeId = "MethodInvocAssign:VarType:" + varType.toString() + "@" + varType.getStartPosition();
            int varTypeIdNum = varType.hashCode();
            
            nameResEdges.add(new Main.Edge(returnTypeId, varTypeId, returnTypeIdNum, varTypeIdNum));
            
            System.out.println("  Added method-return-type-to-variable-type edge: " + returnType.toString() + " -> " + varType.toString());
        }
    }
    
    private void handleMethodInvocationInitialization(MethodInvocation methodInvocation, VariableDeclarationFragment fragment) {
        System.out.println("  Pattern: MethodInvocation used to initialize variable");
        
        // Get the method binding to find the return type
        IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
        if (methodBinding == null) {
            System.out.println("  WARNING: Could not resolve method binding");
            return;
        }
        
        ITypeBinding returnTypeBinding = methodBinding.getReturnType();
        if (returnTypeBinding == null || returnTypeBinding.getName().equals("void")) {
            return;
        }
        
        // Find the method declaration to get the return type AST node
        ASTNode methodDecl = edgeAdder.findDeclaringNodeForBinding(methodBinding);
        if (!(methodDecl instanceof MethodDeclaration)) {
            System.out.println("  WARNING: Could not find method declaration");
            return;
        }
        
        Type returnType = ((MethodDeclaration) methodDecl).getReturnType2();
        if (returnType == null) {
            return;
        }
        
        // Get the type of the variable being declared
        ASTNode parent = fragment.getParent();
        Type varType = null;
        
        if (parent instanceof VariableDeclarationStatement) {
            varType = ((VariableDeclarationStatement) parent).getType();
        } else if (parent instanceof VariableDeclarationExpression) {
            varType = ((VariableDeclarationExpression) parent).getType();
        } else if (parent instanceof FieldDeclaration) {
            varType = ((FieldDeclaration) parent).getType();
        }
        
        if (varType != null) {
            String returnTypeId = "MethodInvocInit:ReturnType:" + returnType.toString() + "@" + returnType.getStartPosition();
            int returnTypeIdNum = returnType.hashCode();
            
            String varTypeId = "MethodInvocInit:VarType:" + varType.toString() + "@" + varType.getStartPosition();
            int varTypeIdNum = varType.hashCode();
            
            nameResEdges.add(new Main.Edge(returnTypeId, varTypeId, returnTypeIdNum, varTypeIdNum));
            
            System.out.println("  Added method-return-type-to-variable-type edge: " + returnType.toString() + " -> " + varType.toString());
        }
    }
    
    private void handleCastVariable(CastExpression castExpression) {
        System.out.println("  Pattern: SimpleName is a variable in a cast expression");
        
        Type castType = castExpression.getType();
        
        IBinding binding = usageNode.resolveBinding();
        if (!(binding instanceof IVariableBinding)) {
            return;
        }
        
        IVariableBinding varBinding = (IVariableBinding) binding;
        ASTNode varDeclaration = edgeAdder.findDeclaringNodeForBinding(varBinding);
        Type varType = edgeAdder.getTypeFromDeclaration(varDeclaration);
        
        if (varType == null) {
            return;
        }
        
        String castTypeId = "CastingTypeToType:CastType:" + castType.toString() + "@" + castType.getStartPosition();
        int castTypeIdNum = castType.hashCode();
        
        String varTypeId = "CastingTypeToType:ExprType:" + varType.toString() + "@" + varType.getStartPosition();
        int varTypeIdNum = varType.hashCode();
        
        nameResEdges.add(new Main.Edge(castTypeId, varTypeId, castTypeIdNum, varTypeIdNum));
        
        System.out.println("  Added cast-type-to-variable-type edge: " + castType.toString() + " -> " + varType.toString());
    }
    
    private void handleCastMethodInvocation(CastExpression castExpression, MethodInvocation methodInvocation) {
        System.out.println("  Pattern: SimpleName is a method in a cast expression");
        
        Type castType = castExpression.getType();
        
        IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
        if (methodBinding == null) {
            return;
        }
        
        ASTNode methodDecl = edgeAdder.findDeclaringNodeForBinding(methodBinding);
        if (!(methodDecl instanceof MethodDeclaration)) {
            return;
        }
        
        Type returnType = ((MethodDeclaration) methodDecl).getReturnType2();
        if (returnType == null) {
            return;
        }
        
        String castTypeId = "CastingTypeToType:CastType:" + castType.toString() + "@" + castType.getStartPosition();
        int castTypeIdNum = castType.hashCode();
        
        String returnTypeId = "CastingTypeToType:ExprType:" + returnType.toString() + "@" + returnType.getStartPosition();
        int returnTypeIdNum = returnType.hashCode();
        
        nameResEdges.add(new Main.Edge(castTypeId, returnTypeId, castTypeIdNum, returnTypeIdNum));
        
        System.out.println("  Added cast-type-to-method-return-type edge: " + castType.toString() + " -> " + returnType.toString());
    }
    
    private void handleTypeArgument(SimpleType typeArg, ParameterizedType parameterizedType) {
        // Resolve the base type binding (analogous to methodInvocation.resolveMethodBinding())
        ITypeBinding baseTypeBinding = parameterizedType.getType().resolveBinding();
        if (baseTypeBinding == null) {
            return;
        }
        
        // Get the generic type declaration (analogous to methodBinding.getMethodDeclaration())
        ITypeBinding genericTypeDeclaration = baseTypeBinding.getTypeDeclaration();
        if (genericTypeDeclaration == null) {
            genericTypeDeclaration = baseTypeBinding;
        }
        
        // Find the declaring node in the AST (analogous to finding MethodDeclaration)
        ASTNode typeDeclarationNode = edgeAdder.findDeclaringNodeForBinding(genericTypeDeclaration);
        if (!(typeDeclarationNode instanceof TypeDeclaration)) {
            return;
        }
        
        TypeDeclaration typeDecl = (TypeDeclaration) typeDeclarationNode;
        List<?> parameters = typeDecl.typeParameters();           // analogous to methodDecl.parameters()
        List<?> arguments = parameterizedType.typeArguments();    // analogous to methodInvocation.arguments()
        
        System.out.println("\nProcessing ParameterizedType: " + parameterizedType.getType().toString());
        
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            Type argumentExpression = (Type) arguments.get(i);
            TypeParameter param = (TypeParameter) parameters.get(i);
            
            // Only create edge for the type argument containing our usageNode
            if (argumentExpression != typeArg) {
                continue;
            }
            
            // Create edge from type argument expression to type parameter
            String argExpressionId = "GenericArgToParam:Expression:" + argumentExpression.toString() + "@" + argumentExpression.getStartPosition();
            int argExpressionIdNum = argumentExpression.hashCode();
            
            String paramId = "GenericArgToParam:Param:" + param.getName().getIdentifier() + "@" + param.getName().getStartPosition();
            int paramIdNum = param.getName().hashCode();
            
            nameResEdges.add(new Main.Edge(argExpressionId, paramId, argExpressionIdNum, paramIdNum));
            
            System.out.println("  Added type-argument-to-type-parameter edge: " + argumentExpression.toString() + "@" + argumentExpression.getStartPosition() + " -> " + param.getName().getIdentifier() + "@" + param.getName().getStartPosition());
        }
    }
}
