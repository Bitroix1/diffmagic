import org.eclipse.jdt.core.dom.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EdgeAdder {
    private List<Main.Edge> nameResEdges;
    private List<CompilationUnit> compilationUnits;
    private Map<String, List<ImportDeclaration>> allImportsMap;
    
    public EdgeAdder(List<CompilationUnit> compilationUnits) {
        this.compilationUnits = compilationUnits;
        this.nameResEdges = new ArrayList<>();
        this.allImportsMap = buildGlobalImportsMap();
    }
    
    private Map<String, List<ImportDeclaration>> buildGlobalImportsMap() {
        Map<String, List<ImportDeclaration>> map = new HashMap<>();
        for (CompilationUnit cu : compilationUnits) {
            for (Object imp : cu.imports()) {
                ImportDeclaration importDecl = (ImportDeclaration) imp;
                if (importDecl.isOnDemand()) continue;
                SimpleName simpleName;
                if (importDecl.getName().isQualifiedName()) {
                    simpleName = ((QualifiedName) importDecl.getName()).getName();
                } else {
                    simpleName = (SimpleName) importDecl.getName();
                }
                map.computeIfAbsent(simpleName.getIdentifier(), k -> new ArrayList<>()).add(importDecl);
            }
        }
        return map;
    }
    
    public List<Main.Edge> getNameResEdges() {
        return nameResEdges;
    }
    
    public void addEdgesForCompilationUnit(CompilationUnit compilationUnit) {
        compilationUnit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding != null && !binding.getClass().getSimpleName().equals("RecoveredTypeBinding") && findDeclaringNodeForBinding(binding) != null) {
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
                    List<ImportDeclaration> matchingImports = allImportsMap.get(node.getIdentifier());
                    if (matchingImports != null && !matchingImports.isEmpty()) {
                    	System.out.println("Found " + node.getIdentifier() + " in imports across " + matchingImports.size() + " file(s)");
                    	String usageId = "NameRes:SimpleName:" + node.toString() + "@" + node.getStartPosition();
                        int usageIdNum = node.hashCode();
                        for (ImportDeclaration declaringImport : matchingImports) {
                            String importDefId = "ImportDeclaration:" + declaringImport.getName().toString() + "@" + declaringImport.getStartPosition();
                            int importDefIdNum = declaringImport.hashCode();
                            nameResEdges.add(new Main.Edge(usageId, importDefId, usageIdNum, importDefIdNum));
                            SimpleName declaringName;
                            if (declaringImport.getName().isSimpleName()) {
                            	declaringName = (SimpleName)declaringImport.getName();
                            } else {
                            	QualifiedName qName = (QualifiedName)declaringImport.getName();
                            	declaringName = qName.getName();
                            }
                            if (declaringName != node) {
	                            int simpleNameDefIdNum = declaringName.hashCode();
	                            String simpleNameDefId = "NameRes:SimpleName:" + declaringName.toString() + "@" + declaringName.getStartPosition();
                            	nameResEdges.add(new Main.Edge(usageId, simpleNameDefId, usageIdNum, simpleNameDefIdNum));
                            }
                        }
                    } else {
                        // Fallback: scope-based name resolution when JDT binding fails
                        // (e.g. inside lambdas with unresolvable enclosing types)
                        SimpleName declaringName = findDeclarationInScope(node);
                        if (declaringName != null) {
                            System.out.println("  Fallback scope resolution found '" + node.getIdentifier() + "' declared at position " + declaringName.getStartPosition());
                            String usageId = "NameRes:SimpleName:" + node.toString() + "@" + node.getStartPosition();
                            int usageIdNum = node.hashCode();

                            // Edge to the declaring SimpleName
                            String simpleNameDefId = "NameRes:SimpleName:" + declaringName.toString() + "@" + declaringName.getStartPosition();
                            int simpleNameDefIdNum = declaringName.hashCode();
                            nameResEdges.add(new Main.Edge(usageId, simpleNameDefId, usageIdNum, simpleNameDefIdNum));

                            // Edge to the declaring base node (fragment/parameter)
                            ASTNode declaringBaseNode = declaringName.getParent();
                            if (declaringBaseNode != null) {
                                String baseNodeId = "NameRes::BaseNode:" + declaringBaseNode.getClass().getSimpleName() + "@" + declaringBaseNode.getStartPosition();
                                int baseNodeIdNum = declaringBaseNode.hashCode();
                                nameResEdges.add(new Main.Edge(usageId, baseNodeId, usageIdNum, baseNodeIdNum));
                            }
                        }
                    }
                    // Also run PatternMatchingVisitor when binding is null (e.g. for SimpleTypeToName edges
                    // where the type cannot be resolved by JDT but the parent/structure edges still apply)
                    ASTNode parentNode = node.getParent();
                    if (parentNode != null) {
                        PatternMatchingVisitor patternVisitor = new PatternMatchingVisitor(node, nameResEdges, EdgeAdder.this);
                        parentNode.accept(patternVisitor);
                    }
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
    
    /**
     * Fallback scope-based name resolution for when JDT binding resolution fails.
     * Walks up the AST from the usage node, searching enclosing scopes for a
     * variable declaration (local variable, parameter, lambda parameter, enhanced-for
     * variable, etc.) with the same identifier that appears before the usage.
     * Returns the declaring SimpleName, or null if none found.
     */
    private SimpleName findDeclarationInScope(SimpleName usageNode) {
        String targetName = usageNode.getIdentifier();
        int usagePosition = usageNode.getStartPosition();
        
        ASTNode current = usageNode.getParent();
        while (current != null) {
            SimpleName found = searchScopeForDeclaration(current, targetName, usagePosition, usageNode);
            if (found != null) {
                return found;
            }
            current = current.getParent();
        }
        return null;
    }
    
    /**
     * Search a single scope node for a variable declaration matching targetName
     * that appears before usagePosition. Only searches direct children  — does not
     * descend into nested blocks/lambdas/anonymous classes.
     */
    private SimpleName searchScopeForDeclaration(ASTNode scope, String targetName, int usagePosition, SimpleName usageNode) {
        // Check method/lambda parameters
        if (scope instanceof MethodDeclaration) {
            for (Object param : ((MethodDeclaration) scope).parameters()) {
                SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
                if (svd.getName().getIdentifier().equals(targetName) && svd.getName() != usageNode) {
                    return svd.getName();
                }
            }
        }
        
        if (scope instanceof LambdaExpression) {
            for (Object param : ((LambdaExpression) scope).parameters()) {
                if (param instanceof SingleVariableDeclaration) {
                    SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
                    if (svd.getName().getIdentifier().equals(targetName) && svd.getName() != usageNode) {
                        return svd.getName();
                    }
                } else if (param instanceof VariableDeclarationFragment) {
                    VariableDeclarationFragment vdf = (VariableDeclarationFragment) param;
                    if (vdf.getName().getIdentifier().equals(targetName) && vdf.getName() != usageNode) {
                        return vdf.getName();
                    }
                }
            }
        }
        
        // Check enhanced-for variable
        if (scope instanceof EnhancedForStatement) {
            SingleVariableDeclaration param = ((EnhancedForStatement) scope).getParameter();
            if (param.getName().getIdentifier().equals(targetName) && param.getName() != usageNode) {
                return param.getName();
            }
        }
        
        // Check local variable declarations in blocks (only direct statements, not nested blocks)
        if (scope instanceof Block) {
            // Search statements in reverse to find the closest preceding declaration
            SimpleName best = null;
            for (Object stmt : ((Block) scope).statements()) {
                if (stmt instanceof VariableDeclarationStatement) {
                    VariableDeclarationStatement vds = (VariableDeclarationStatement) stmt;
                    for (Object frag : vds.fragments()) {
                        VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
                        if (vdf.getName().getIdentifier().equals(targetName)
                                && vdf.getName().getStartPosition() < usagePosition
                                && vdf.getName() != usageNode) {
                            best = vdf.getName();
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        
        // Check for-statement initializers
        if (scope instanceof ForStatement) {
            for (Object init : ((ForStatement) scope).initializers()) {
                if (init instanceof VariableDeclarationExpression) {
                    for (Object frag : ((VariableDeclarationExpression) init).fragments()) {
                        VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
                        if (vdf.getName().getIdentifier().equals(targetName) && vdf.getName() != usageNode) {
                            return vdf.getName();
                        }
                    }
                }
            }
        }
        
        // Check catch clause parameter
        if (scope instanceof CatchClause) {
            SingleVariableDeclaration exception = ((CatchClause) scope).getException();
            if (exception.getName().getIdentifier().equals(targetName) && exception.getName() != usageNode) {
                return exception.getName();
            }
        }
        
        // Check field declarations in enclosing type
        if (scope instanceof TypeDeclaration) {
            for (FieldDeclaration field : ((TypeDeclaration) scope).getFields()) {
                for (Object frag : field.fragments()) {
                    VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
                    if (vdf.getName().getIdentifier().equals(targetName) && vdf.getName() != usageNode) {
                        return vdf.getName();
                    }
                }
            }
        }
        
        return null;
    }
}
