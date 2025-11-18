import org.eclipse.jdt.core.dom.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EdgeAdder {
    private List<Main.Edge> nameResEdges;
    private List<CompilationUnit> compilationUnits;
    private List<String> javaFiles;
    
    public EdgeAdder(List<CompilationUnit> compilationUnits, List<String> javaFiles) {
        this.compilationUnits = compilationUnits;
        this.javaFiles = javaFiles;
        this.nameResEdges = new ArrayList<>();
    }
    
    public List<Main.Edge> getNameResEdges() {
        return nameResEdges;
    }
    
    public void addEdgesForCompilationUnit(CompilationUnit compilationUnit) {
        // Default SimpleName visitor
        compilationUnit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                String usageId = node.getClass().getSimpleName() + "@" + node.getStartPosition();
                int usageIdNum = node.hashCode();

                if (binding != null) {
                    System.out.println("Processing SimpleName: '" + node.getIdentifier() + "' at position " + node.getStartPosition());
                    System.out.println("  Binding type: " + binding.getClass().getSimpleName());
                    System.out.println("  Binding key: " + binding.getKey());
                    
                    // Try to find the declaring node in the current compilation unit first
                    ASTNode declaringNode = compilationUnit.findDeclaringNode(binding);
                    
                    if (declaringNode != null) {
                        // The binding resolves to a node in this compilation unit
                        String defId = binding.getKey();
                        int defIdNum = declaringNode.hashCode();

                        nameResEdges.add(new Main.Edge(usageId, defId, usageIdNum, defIdNum));
                        System.out.println("  Added local edge: " + usageId + " -> " + defId + " (defIdNum: " + defIdNum + ")");
                    } else {
                        // Try to find the declaring node in ALL compilation units
                        ASTNode foundDeclaringNode = null;
                        String foundInFile = null;
                        
                        for (int j = 0; j < compilationUnits.size(); j++) {
                            CompilationUnit otherUnit = compilationUnits.get(j);
                            foundDeclaringNode = otherUnit.findDeclaringNode(binding);
                            if (foundDeclaringNode != null) {
                                foundInFile = javaFiles.get(j);
                                break;
                            }
                        }
                        
                        if (foundDeclaringNode != null) {
                            // Found the binding in another compilation unit
                            String defId = binding.getKey();
                            int defIdNum = foundDeclaringNode.hashCode();

                            nameResEdges.add(new Main.Edge(usageId, defId, usageIdNum, defIdNum));
                            System.out.println("  Added cross-file edge: " + usageId + " -> " + defId + " (defIdNum: " + defIdNum + ")");
                            System.out.println("  Found in file: " + new File(foundInFile).getName());
                        } else {
                            // Check if this is a library binding (like System.out.println)
                            if (binding.getKey().startsWith("Ljava/") || binding.getKey().contains("java.lang")) {
                                System.out.println("  Skipped library binding: " + binding.getKey());
                            } else {
                                // This might be a binding that should be resolvable but isn't
                                System.out.println("  WARNING: Binding not resolved in any compilation unit");
                                System.out.println("  Binding details - Key: " + binding.getKey() + ", Kind: " + binding.getKind());
                                
                                // Still add the edge but with -1 as defIdNum to indicate unresolved
                                String defId = binding.getKey();
                                nameResEdges.add(new Main.Edge(usageId, defId, usageIdNum, -1));
                                System.out.println("  Added unresolved edge: " + usageId + " -> " + defId + " (defIdNum: -1)");
                            }
                        }
                    }
                } else {
                    System.out.println("No binding for SimpleName: '" + node.getIdentifier() + "' at position " + node.getStartPosition());
                }
                
                return super.visit(node);
            }
        });

        // Argument to Parameter visitor
        compilationUnit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding methodBinding = node.resolveMethodBinding();
                
                if (methodBinding != null) {
                    System.out.println("\nProcessing MethodInvocation: " + node.getName().getIdentifier());
                    
                    // Get the method declaration node
                    ASTNode methodDeclaration = null;
                    
                    // Search in all compilation units for the method declaration
                    for (int j = 0; j < compilationUnits.size(); j++) {
                        CompilationUnit otherUnit = compilationUnits.get(j);
                        methodDeclaration = otherUnit.findDeclaringNode(methodBinding.getMethodDeclaration());
                        if (methodDeclaration != null) {
                            break;
                        }
                    }
                    
                    if (methodDeclaration instanceof MethodDeclaration) {
                        MethodDeclaration methodDecl = (MethodDeclaration) methodDeclaration;
                        List<?> parameters = methodDecl.parameters();
                        List<?> arguments = node.arguments();
                        
                        System.out.println("  Found method declaration with " + parameters.size() + " parameters");
                        System.out.println("  Method call has " + arguments.size() + " arguments");
                        
                        // Match arguments to parameters by position
                        int matchCount = Math.min(parameters.size(), arguments.size());
                        
                        for (int k = 0; k < matchCount; k++) {
                            Object paramObj = parameters.get(k);
                            Object argObj = arguments.get(k);
                            
                            // Extract SimpleName from parameter
                            SimpleName paramName = null;
                            if (paramObj instanceof SingleVariableDeclaration) {
                                SingleVariableDeclaration param = (SingleVariableDeclaration) paramObj;
                                paramName = param.getName();
                            }
                            
                            // Extract SimpleName from argument (if it's a simple name reference)
                            SimpleName argName = null;
                            if (argObj instanceof SimpleName) {
                                argName = (SimpleName) argObj;
                            } else if (argObj instanceof Expression) {
                                // Try to extract SimpleName from more complex expressions
                                Expression argExpr = (Expression) argObj;
                                argExpr.accept(new ASTVisitor() {
                                    @Override
                                    public boolean visit(SimpleName name) {
                                        // This will visit the first SimpleName found
                                        return false; // Don't continue traversing
                                    }
                                });
                            }
                            
                            // Create edge if both names exist
                            if (paramName != null && argName != null) {
                                String argUsageId = argName.getClass().getSimpleName() + "@" + argName.getStartPosition();
                                int argUsageIdNum = argName.hashCode();
                                
                                String paramDefId = "Param:" + paramName.getIdentifier() + "@" + paramName.getStartPosition();
                                int paramDefIdNum = paramName.hashCode();
                                
                                nameResEdges.add(new Main.Edge(argUsageId, paramDefId, argUsageIdNum, paramDefIdNum));
                                
                                System.out.println("  Added argument-to-parameter edge [" + k + "]: " + 
                                                 argName.getIdentifier() + " -> " + paramName.getIdentifier());
                                System.out.println("    From: " + argUsageId + " (ID: " + argUsageIdNum + ")");
                                System.out.println("    To: " + paramDefId + " (ID: " + paramDefIdNum + ")");
                            }
                        }
                    } else {
                        System.out.println("  Method declaration not found in any compilation unit");
                    }
                }
                
                return super.visit(node);
            }
        });

        // ReturnStatement visitor for return type matching
        compilationUnit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                Expression returnExpression = node.getExpression();
                
                if (returnExpression == null) {
                    // void return or empty return statement
                    return super.visit(node);
                }
                
                // Find the enclosing method declaration
                ASTNode parent = node.getParent();
                while (parent != null && !(parent instanceof MethodDeclaration)) {
                    parent = parent.getParent();
                }
                
                if (parent instanceof MethodDeclaration) {
                    MethodDeclaration methodDecl = (MethodDeclaration) parent;
                    Type returnType = methodDecl.getReturnType2();
                    
                    // Skip if method has no return type (constructor) or void return type
                    if (returnType == null || (returnType.isPrimitiveType() && 
                        ((PrimitiveType) returnType).getPrimitiveTypeCode() == PrimitiveType.VOID)) {
                        return super.visit(node);
                    }
                    
                    System.out.println("\nProcessing ReturnStatement in method: " + methodDecl.getName().getIdentifier());
                    
                    String methodReturnTypeId = "MethodReturnType:" + returnType.toString() + "@" + returnType.getStartPosition();
                    int methodReturnTypeIdNum = returnType.hashCode();
                    
                    // Check if returning a variable (SimpleName) or a literal
                    if (returnExpression instanceof SimpleName) {
                        // Case 1: Returning a variable - create edge from variable's type declaration to method return type
                        SimpleName returnVar = (SimpleName) returnExpression;
                        IBinding binding = returnVar.resolveBinding();
                        
                        if (binding instanceof IVariableBinding) {
                            IVariableBinding varBinding = (IVariableBinding) binding;
                            
                            // Find the variable declaration to get its type node
                            ASTNode varDeclaration = null;
                            
                            // Search in current compilation unit first
                            varDeclaration = compilationUnit.findDeclaringNode(varBinding);
                            
                            // If not found, search in all compilation units
                            if (varDeclaration == null) {
                                for (int j = 0; j < compilationUnits.size(); j++) {
                                    CompilationUnit otherUnit = compilationUnits.get(j);
                                    varDeclaration = otherUnit.findDeclaringNode(varBinding);
                                    if (varDeclaration != null) {
                                        break;
                                    }
                                }
                            }
                            
                            // Extract the type node from the variable declaration
                            Type varType = null;
                            if (varDeclaration instanceof VariableDeclarationFragment) {
                                ASTNode parentNode = varDeclaration.getParent();
                                if (parentNode instanceof VariableDeclarationStatement) {
                                    varType = ((VariableDeclarationStatement) parentNode).getType();
                                } else if (parentNode instanceof VariableDeclarationExpression) {
                                    varType = ((VariableDeclarationExpression) parentNode).getType();
                                } else if (parentNode instanceof FieldDeclaration) {
                                    varType = ((FieldDeclaration) parentNode).getType();
                                }
                            } else if (varDeclaration instanceof SingleVariableDeclaration) {
                                varType = ((SingleVariableDeclaration) varDeclaration).getType();
                            }
                            
                            if (varType != null) {
                                System.out.println("  Return variable: " + returnVar.getIdentifier());
                                System.out.println("  Variable type: " + varType.toString());
                                System.out.println("  Method return type: " + returnType.toString());
                                
                                // Create edge from variable's type to method's return type
                                String varTypeId = "ReturnVarType:" + varType.toString() + "@" + varType.getStartPosition();
                                int varTypeIdNum = varType.hashCode();
                                
                                nameResEdges.add(new Main.Edge(varTypeId, methodReturnTypeId, varTypeIdNum, methodReturnTypeIdNum));
                                
                                System.out.println("  Added variable-type-to-method-return-type edge: " + varTypeId + " -> " + methodReturnTypeId);
                                System.out.println("    From: " + varTypeId + " (ID: " + varTypeIdNum + ")");
                                System.out.println("    To: " + methodReturnTypeId + " (ID: " + methodReturnTypeIdNum + ")");
                            } else {
                                System.out.println("  WARNING: Could not find type node for variable: " + returnVar.getIdentifier());
                            }
                        }
                    } else {
                        // Case 2: Returning a literal or expression - create edge directly from literal to method return type
                        System.out.println("  Return expression is a literal/expression: " + returnExpression.getClass().getSimpleName());
                        
                        String returnLiteralId = "ReturnLiteral:" + returnExpression.getClass().getSimpleName() + "@" + returnExpression.getStartPosition();
                        int returnLiteralIdNum = returnExpression.hashCode();
                        
                        nameResEdges.add(new Main.Edge(returnLiteralId, methodReturnTypeId, returnLiteralIdNum, methodReturnTypeIdNum));
                        
                        System.out.println("  Added literal-to-method-return-type edge: " + returnLiteralId + " -> " + methodReturnTypeId);
                        System.out.println("    From: " + returnLiteralId + " (ID: " + returnLiteralIdNum + ")");
                        System.out.println("    To: " + methodReturnTypeId + " (ID: " + methodReturnTypeIdNum + ")");
                    }
                }
                
                return super.visit(node);
            }
        });

        // MethodInvocation visitor for method name resolution
        compilationUnit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                SimpleName methodCallName = node.getName();
                IMethodBinding methodBinding = node.resolveMethodBinding();
                
                if (methodBinding != null) {
                    System.out.println("\nProcessing MethodInvocation name resolution: " + methodCallName.getIdentifier());
                    
                    // Search for the method declaration in all compilation units
                    ASTNode methodDeclaration = null;
                    
                    for (int j = 0; j < compilationUnits.size(); j++) {
                        CompilationUnit otherUnit = compilationUnits.get(j);
                        methodDeclaration = otherUnit.findDeclaringNode(methodBinding.getMethodDeclaration());
                        if (methodDeclaration != null) {
                            break;
                        }
                    }
                    
                    if (methodDeclaration instanceof MethodDeclaration) {
                        MethodDeclaration methodDecl = (MethodDeclaration) methodDeclaration;
                        SimpleName methodDeclName = methodDecl.getName();
                        
                        System.out.println("  Found method declaration: " + methodDeclName.getIdentifier());
                        
                        // Create edge from method call name to method declaration name
                        String callNameId = "MethodCallName:" + methodCallName.getIdentifier() + "@" + methodCallName.getStartPosition();
                        int callNameIdNum = methodCallName.hashCode();
                        
                        String declNameId = "MethodDeclName:" + methodDeclName.getIdentifier() + "@" + methodDeclName.getStartPosition();
                        int declNameIdNum = methodDeclName.hashCode();
                        
                        nameResEdges.add(new Main.Edge(callNameId, declNameId, callNameIdNum, declNameIdNum));
                        
                        System.out.println("  Added method-call-to-declaration edge: " + callNameId + " -> " + declNameId);
                        System.out.println("    From: " + callNameId + " (ID: " + callNameIdNum + ")");
                        System.out.println("    To: " + declNameId + " (ID: " + declNameIdNum + ")");
                    } else {
                        System.out.println("  Method declaration not found (possibly library method)");
                    }
                } else {
                    System.out.println("  WARNING: Could not resolve method binding for: " + methodCallName.getIdentifier());
                }
                
                return super.visit(node);
            }
        });
    }
}
