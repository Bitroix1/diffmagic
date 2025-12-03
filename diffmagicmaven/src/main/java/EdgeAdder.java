import org.eclipse.jdt.core.dom.*;
import java.util.ArrayList;
import java.util.List;

public class EdgeAdder {
    private List<Main.Edge> nameResEdges;
    private List<CompilationUnit> compilationUnits;
    
    public EdgeAdder(List<CompilationUnit> compilationUnits) {
        this.compilationUnits = compilationUnits;
        this.nameResEdges = new ArrayList<>();
    }
    
    public List<Main.Edge> getNameResEdges() {
        return nameResEdges;
    }
    
    public void addEdgesForCompilationUnit(CompilationUnit compilationUnit) {
        compilationUnit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding != null) {
                    System.out.println("Processing SimpleName: '" + node.getIdentifier() + "' at position " + node.getStartPosition());
                    System.out.println("  Binding type: " + binding.getClass().getSimpleName());
                    System.out.println("  Binding key: " + binding.getKey());
                    
                    // Add standard name resolution edges
                    addNameResolutionEdge(compilationUnit, node, binding);
                    
                    // Use pattern matching visitor on the parent context where this SimpleName is used
                    ASTNode parent = node.getParent();
                    if (parent != null) {
                        PatternMatchingVisitor patternVisitor = new PatternMatchingVisitor(node, nameResEdges, EdgeAdder.this);
                        parent.accept(patternVisitor);
                    }
                } else {
                    System.out.println("No binding for SimpleName: '" + node.getIdentifier() + "' at position " + node.getStartPosition());
                }
                return super.visit(node);
            }
            
            @Override
            public boolean visit(MethodInvocation methodInvocation) {
                handleMethodInvocationArguments(methodInvocation);
                return super.visit(methodInvocation);
            }
            
            @Override
            public boolean visit(ReturnStatement node) {
                // Handle literal return expressions
                Expression returnExpression = node.getExpression();
                if (returnExpression != null && !(returnExpression instanceof SimpleName)) {
                    handleReturnLiteral(node, returnExpression);
                }
                return super.visit(node);
            }
        });
    }
    
    private void handleMethodInvocationArguments(MethodInvocation methodInvocation) {
        // Handle all arguments in method invocation
        IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
        if (methodBinding == null) {
            return;
        }
        
        ASTNode methodDeclaration = findDeclaringNodeForBinding(methodBinding.getMethodDeclaration());
        if (!(methodDeclaration instanceof MethodDeclaration)) {
            return;
        }
        
        MethodDeclaration methodDecl = (MethodDeclaration) methodDeclaration;
        List<?> parameters = methodDecl.parameters();
        List<?> arguments = methodInvocation.arguments();
        
        System.out.println("\nProcessing MethodInvocation: " + methodInvocation.getName().getIdentifier());
        
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            Expression argumentExpression = (Expression) arguments.get(i);
            SingleVariableDeclaration param = (SingleVariableDeclaration) parameters.get(i);
            
            // Create edge from argument expression to parameter
            String argExpressionId = "ArgToParam:Expression:" + argumentExpression.getClass().getSimpleName() + "@" + argumentExpression.getStartPosition();
            int argExpressionIdNum = argumentExpression.hashCode();
            
            String paramId = "ArgToParam:Param:" + param.getName().getIdentifier() + "@" + param.getStartPosition();
            int paramIdNum = param.hashCode();
            
            nameResEdges.add(new Main.Edge(argExpressionId, paramId, argExpressionIdNum, paramIdNum));
            
            System.out.println("  Added argument-to-parameter edge: " + argumentExpression.getClass().getSimpleName() + "@" + argumentExpression.getStartPosition() + " -> " + param.getName().getIdentifier() + "@" + param.getStartPosition());
        }
    }
    
    ASTNode findDeclaringNodeForBinding(IBinding binding) {
        for (CompilationUnit cu : compilationUnits) {
            ASTNode declaringNode = cu.findDeclaringNode(binding);
            if (declaringNode != null) {
                return declaringNode;
            }
        }
        return null;
    }
    
    Type getTypeFromDeclaration(ASTNode varDeclaration) {
        if (varDeclaration instanceof VariableDeclarationFragment) {
            ASTNode parentNode = varDeclaration.getParent();
            if (parentNode instanceof VariableDeclarationStatement) {
                return ((VariableDeclarationStatement) parentNode).getType();
            } else if (parentNode instanceof VariableDeclarationExpression) {
                return ((VariableDeclarationExpression) parentNode).getType();
            } else if (parentNode instanceof FieldDeclaration) {
                return ((FieldDeclaration) parentNode).getType();
            }
        } else if (varDeclaration instanceof SingleVariableDeclaration) {
            return ((SingleVariableDeclaration) varDeclaration).getType();
        }
        return null;
    }
    
    private void handleReturnLiteral(ReturnStatement returnStatement, Expression returnExpression) {
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
            
            System.out.println("\nProcessing ReturnStatement with literal in method: " + methodDecl.getName().getIdentifier());
            
            String methodReturnTypeId = "MethodReturnType:" + returnType.toString() + "@" + returnType.getStartPosition();
            int methodReturnTypeIdNum = returnType.hashCode();
            
            String returnLiteralId = "ReturnLiteral:" + returnExpression.getClass().getSimpleName() + "@" + returnExpression.getStartPosition();
            int returnLiteralIdNum = returnExpression.hashCode();
            
            nameResEdges.add(new Main.Edge(returnLiteralId, methodReturnTypeId, returnLiteralIdNum, methodReturnTypeIdNum));
            
            System.out.println("  Added literal-to-method-return-type edge: " + returnLiteralId + " -> " + methodReturnTypeId);
        }
    }
    
    private void addNameResolutionEdge(CompilationUnit compilationUnit, SimpleName node, IBinding binding) {
        String usageId = "NameRes:SimpleName:" + node.toString() + "@" + node.getStartPosition();
        int usageIdNum = node.hashCode();
        
        // Try to find declaring node in current compilation unit first, then in all units
        ASTNode declaringNode = compilationUnit.findDeclaringNode(binding);
        if (declaringNode == null) {
            declaringNode = findDeclaringNodeForBinding(binding);
        }
        
        if (declaringNode != null) {
            String simpleNameBaseNode = "NameRes::BaseNode:" + declaringNode.getClass().getSimpleName() + "@" + declaringNode.getStartPosition();
            int idNumBaseNode = declaringNode.hashCode();
        	nameResEdges.add(new Main.Edge(usageId, simpleNameBaseNode, usageIdNum, idNumBaseNode));
            //System.out.println("  Added SimpleName-to-SimpleName edge: " + usageId + " -> " + simpleNameDefId);
            
            SimpleName declaringName = findDeclaringSimpleName(declaringNode, binding);
            if (declaringName != null && declaringName != node) {
                String simpleNameDefId = "NameRes:SimpleName:" + declaringName.toString() + "@" + declaringName.getStartPosition();
                int simpleNameDefIdNum = declaringName.hashCode();
                nameResEdges.add(new Main.Edge(usageId, simpleNameDefId, usageIdNum, simpleNameDefIdNum));
                System.out.println("  Added SimpleName-to-SimpleName edge: " + usageId + " -> " + simpleNameDefId);
            }
        } else {
            if (binding.getKey().startsWith("Ljava/") || binding.getKey().contains("java.lang")) {
                System.out.println("  Skipped library binding: " + binding.getKey());
            } else {
                System.out.println("  WARNING: Binding not resolved in any compilation unit");
                System.out.println("  Binding details - Key: " + binding.getKey() + ", Kind: " + binding.getKind());
            }
        }
    }
    
    private SimpleName findDeclaringSimpleName(ASTNode declaringNode, IBinding binding) {
        if (declaringNode instanceof SimpleName) {
            return (SimpleName) declaringNode;
        }
        
        // For variable declarations, find the SimpleName within the declaration
        if (declaringNode instanceof VariableDeclarationFragment) {
            return ((VariableDeclarationFragment) declaringNode).getName();
        }
        
        if (declaringNode instanceof SingleVariableDeclaration) {
            return ((SingleVariableDeclaration) declaringNode).getName();
        }
        
        if (declaringNode instanceof MethodDeclaration) {
            return ((MethodDeclaration) declaringNode).getName();
        }
        
        if (declaringNode instanceof TypeDeclaration) {
            return ((TypeDeclaration) declaringNode).getName();
        }
        
        if (declaringNode instanceof EnumDeclaration) {
            return ((EnumDeclaration) declaringNode).getName();
        }
        
        if (declaringNode instanceof AnnotationTypeDeclaration) {
            return ((AnnotationTypeDeclaration) declaringNode).getName();
        }
        
        if (declaringNode instanceof EnumConstantDeclaration) {
            return ((EnumConstantDeclaration) declaringNode).getName();
        }
        
        // If we can't find the SimpleName directly, search for it in children
        final SimpleName[] result = new SimpleName[1];
        declaringNode.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.resolveBinding() != null && name.resolveBinding().getKey().equals(binding.getKey())) {
                    result[0] = name;
                    return false;
                }
                return true;
            }
        });
        
        return result[0];
    }
}
