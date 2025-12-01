import org.eclipse.jdt.core.dom.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyntaxVisitor extends ASTVisitor {
    private CompilationUnit cu;
    public Map<String, Object> syntaxMap = null;
    private char[] source;
    private static int uniqueId = 1;
    
    // Map to cache content IDs so the same content gets the same ID
    private static Map<String, Integer> contentIdCache = new HashMap<>();
    private static int nextContentId = 1;
    
    // Constructor with only cu and source
    SyntaxVisitor(CompilationUnit cu, char[] source) {
        this.cu = cu;
        this.source = source;
    }
    
    // Constructor that accepts a content ID cache
    SyntaxVisitor(CompilationUnit cu, char[] source, Map<String, Integer> externalContentIdCache) {
        this.cu = cu;
        this.source = source;
        
        // Use the provided content ID cache
        if (externalContentIdCache != null) {
            contentIdCache = externalContentIdCache;
            
            // Find the highest content ID to set nextContentId
            for (int id : externalContentIdCache.values()) {
                if (id >= nextContentId) {
                    nextContentId = id + 1;
                }
            }
        }
    }

    // Method to set the starting uniqueId value
    public static void setStartingUniqueId(int startId) {
        uniqueId = startId;
    }
    
    // Method to get the current uniqueId value
    public static int getLastUniqueId() {
        return uniqueId - 1;
    }
    
    // Method to get the content ID cache
    public static Map<String, Integer> getContentIdCache() {
        return new HashMap<>(contentIdCache);
    }
    
    // Get or create a content ID for a specific content
    private int getContentId(String content) {
        if (contentIdCache.containsKey(content)) {
            return contentIdCache.get(content);
        } else {
            int id = nextContentId++;
            contentIdCache.put(content, id);
            return id;
        }
    }
    
    // Create an Atom element for the syntax tree
    private void createAtom(Map<String, Object> nodeMap, String content, String position) {
        nodeMap.put("node_kind", "atom");
        nodeMap.put("content_id", getContentId(content));
        nodeMap.put("content", content);
        nodeMap.put("position", position);
    }
    
    // Create a List element for the syntax tree
    private void createList(Map<String, Object> nodeMap, String openContent, String openPosition, String closeContent, String closePosition) {
        nodeMap.put("node_kind", "list");
        nodeMap.put("content_id", getContentId(openContent));
        nodeMap.put("open_content", openContent);
        nodeMap.put("open_position", openPosition);
        nodeMap.put("close_content", closeContent);
        nodeMap.put("close_position", closePosition);
		nodeMap.put("num_descendants", 0);
    }
    
    private Object getNodeSyntax(ASTNode node) {
		if (node == null) {
	        return null;
	    }
		
		SyntaxVisitor visitor = new SyntaxVisitor(this.cu, this.source);
		node.accept(visitor);
		return visitor.syntaxMap == null ? node.getClass().getSimpleName() + " IS NOT IMPLEMENTED!!!" : visitor.syntaxMap;
	}
	
	private Object getListOf(List<?> list) {
		if (list == null || list.isEmpty()) {
			return null;
		}
		
		// If there's only one element, return it directly without wrapping in a list
		if (list.size() == 1) {
			Object object = list.get(0);
			if (object instanceof ASTNode) {
				ASTNode node = (ASTNode)object;
				return getNodeSyntax(node);
			}
			return object;
		}
		
		// For multiple elements, track the positions of first and last nodes
		List<Object> objectList = new ArrayList<>();
		int firstNodeStart = -1;
		int lastNodeEnd = -1;
		
		for (Object object : list) {
			if (object instanceof ASTNode) {
				ASTNode node = (ASTNode)object;
				
				// Track positions of first and last valid node
				if (firstNodeStart == -1) {
					firstNodeStart = node.getStartPosition();
				}
				lastNodeEnd = node.getStartPosition() + node.getLength();
				
				// Process node and add to list
				Object result = getNodeSyntax(node);
				if (result != null) {
					objectList.add(result);
				}
			}
		}
		
		if (objectList.isEmpty()) {
			return null;
		}
		
		// Convert positions to strings
		String openPosition = firstNodeStart != -1 ? getPositionString(firstNodeStart, 0) : "0:0-0";
		String closePosition = lastNodeEnd != -1 ? getPositionString(lastNodeEnd, 0) : "0:0-0";
		
		// Create a proper list node with the determined positions
		Map<String, Object> listNode = new HashMap<>();
		createList(listNode, "", openPosition, "", closePosition);
		listNode.put("children", objectList);
		return listNode;
	}

    // Get position as a string in format "line:column-endColumn"
    private String getPositionString(int startPosition, int length) {
        if (startPosition < 0 || length < 0) {
            return "0:0-0";
        }
        // Convert from 1-indexed (Eclipse JDT) to 0-indexed
        int startLine = cu.getLineNumber(startPosition) - 1;
        int startCol = cu.getColumnNumber(startPosition);
        int endCol = startCol + length;
        
        return startLine + ":" + startCol + "-" + endCol;
    }

	// Find the position of a specific character in the source
	private String findCharacterPosition(char character, int searchStart, int searchEnd) {
		for (int i = searchStart; i < searchEnd && i < source.length; i++) {
			if (source[i] == character) {
				int line = cu.getLineNumber(i) - 1;
				int col = cu.getColumnNumber(i) - 1;
				return line + ":" + col + "-" + (col + 1);
			}
		}
		return "0:0-0"; // Fallback if not found
	}

	// Find the exact position of list delimiters
	private String findOpenPosition(ASTNode node) {
		int nodeStart = node.getStartPosition();
		
		if (node instanceof Block || node instanceof TypeDeclaration) {
			// For blocks and type declarations, find the '{'
			return findCharacterPosition('{', nodeStart, nodeStart + 30);
		} else if (node instanceof MethodInvocation) {
			// For method invocations, find the '('
			MethodInvocation mi = (MethodInvocation) node;
			int nameEnd = mi.getName().getStartPosition() + mi.getName().getLength();
			return findCharacterPosition('(', nameEnd, nameEnd + 5);
		}
		
		// Default: use the node's start position
		return getPositionString(nodeStart, 0);
	}

	private String findClosePosition(ASTNode node) {
		int nodeEnd = node.getStartPosition() + node.getLength();
		
		if (node instanceof Block || node instanceof TypeDeclaration) {
			// For blocks and type declarations, find the '}'
			return findCharacterPosition('}', nodeEnd - 10, nodeEnd);
		} else if (node instanceof MethodInvocation) {
			// For method invocations, find the ')'
			return findCharacterPosition(')', nodeEnd - 5, nodeEnd);
		}
		
		// Default: use the node's end position
		return getPositionString(nodeEnd, 0);
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(AnnotationTypeDeclaration node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(AnnotationTypeMemberDeclaration node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(AnonymousClassDeclaration node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ArrayAccess node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ArrayCreation node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ArrayInitializer node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ArrayType node) {
		syntaxMap = new HashMap<>();
		String content = node.getElementType().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(AssertStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(Assignment node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object leftHandSide = getNodeSyntax(node.getLeftHandSide());
		if (leftHandSide != null) {
			children.add(leftHandSide);
		}
		Object rightHandSide = getNodeSyntax(node.getRightHandSide());
		if (rightHandSide != null) {
			children.add(rightHandSide);
		}
        
        Map<String, Object> operatorMap = new HashMap<>();
	    String operatorText = node.getOperator().toString();

	    String operatorPosition = getPositionString(node.getLeftHandSide().getStartPosition() + node.getRightHandSide().getLength(), operatorText.length());
	    createAtom(operatorMap, operatorText, operatorPosition);
		syntaxMap.put("kind", "Normal");
	    children.add(operatorMap);
        
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());

		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(Block node) {
		syntaxMap = new HashMap<>();
		
		String openPosition = findOpenPosition(node);
		String closePosition = findClosePosition(node);
		
		createList(syntaxMap, "{", openPosition, "}", closePosition);
		
		ArrayList<Object> children = new ArrayList<>();
		
		// Special handling for block - process statements and extract expressions
		List<?> statements = node.statements();
		if (statements != null && !statements.isEmpty()) {
			// If there's only one statement
			if (statements.size() == 1) {
				Object stmt = statements.get(0);
				if (stmt instanceof ExpressionStatement) {
					Expression expr = ((ExpressionStatement) stmt).getExpression();
					Object result = getNodeSyntax(expr);
					if (result != null) {
						children.add(result);
					}
				} else if (stmt instanceof ASTNode) {
					Object result = getNodeSyntax((ASTNode) stmt);
					if (result != null) {
						children.add(result);
					}
				}
			} else {
				// Multiple statements - track positions and extract expressions
				List<Object> objectList = new ArrayList<>();
				int firstNodeStart = -1;
				int lastNodeEnd = -1;
				
				for (Object stmt : statements) {
					if (stmt instanceof ASTNode) {
						ASTNode stmtNode = (ASTNode) stmt;
						
						// Track positions
						if (firstNodeStart == -1) {
							firstNodeStart = stmtNode.getStartPosition();
						}
						lastNodeEnd = stmtNode.getStartPosition() + stmtNode.getLength();
						
						// Extract expression if this is an ExpressionStatement
						Object result;
						if (stmt instanceof ExpressionStatement) {
							Expression expr = ((ExpressionStatement) stmt).getExpression();
							result = getNodeSyntax(expr);
						} else {
							result = getNodeSyntax(stmtNode);
						}
						
						if (result != null) {
							objectList.add(result);
						}
					}
				}
				
				if (!objectList.isEmpty()) {
					String openPos = firstNodeStart != -1 ? getPositionString(firstNodeStart, 0) : "0:0-0";
					String closePos = lastNodeEnd != -1 ? getPositionString(lastNodeEnd, 0) : "0:0-0";
					
					Map<String, Object> listNode = new HashMap<>();
					createList(listNode, "", openPos, "", closePos);
					listNode.put("children", objectList);
					children.add(listNode);
				}
			}
		}
		
		syntaxMap.put("children", children);
		syntaxMap.put("uniqueId", node.hashCode());
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 * <p>Note: {@link LineComment} and {@link BlockComment} nodes are
	 * not considered part of main structure of the AST. This method will
	 * only be called if a client goes out of their way to visit this
	 * kind of node explicitly.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(BlockComment node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(BooleanLiteral node) {
		syntaxMap = new HashMap<>();
		String content = Boolean.toString(node.booleanValue());

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(BreakStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(CastExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(CatchClause node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "{", getPositionString(node.getStartPosition(), 1), "}", getPositionString(node.getStartPosition() + node.getLength(), 1));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeBody = getNodeSyntax(node.getBody());
		if (nodeBody != null) {
			children.add(nodeBody);
		}
		Object nodeException = getNodeSyntax(node.getException());
		if (nodeException != null) {
			children.add(nodeException);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(CharacterLiteral node) {
		syntaxMap = new HashMap<>();
		String content = node.getEscapedValue().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "String(StringLiteral)");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ClassInstanceCreation node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", "0:0-0", "", getPositionString(node.getStartPosition(), node.getLength()));
		
        ArrayList<Object> children = new ArrayList<>();
		Object arguments = getListOf(node.arguments());
		if (arguments != null) {
			children.add(arguments);
		}
		Object anonymousClassDeclaration = getNodeSyntax(node.getAnonymousClassDeclaration());
		if (anonymousClassDeclaration != null) {
			children.add(anonymousClassDeclaration);
		}
		Object expression = getNodeSyntax(node.getExpression());
		if (expression != null) {
			children.add(expression);
		}
		Object nodeType = getNodeSyntax(node.getType());
		if (nodeType != null) {
			children.add(nodeType);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(CompilationUnit node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", "0:0-0", "", getPositionString(node.getStartPosition(), node.getLength()));
		
        ArrayList<Object> children = new ArrayList<>();
		// Get package name directly (not wrapped in PackageDeclaration)
		if (node.getPackage() != null) {
			Object packageName = getNodeSyntax(node.getPackage().getName());
			if (packageName != null) {
				children.add(packageName);
			}
		}
		
		// Handle imports - unwrap if there's exactly one
		List<?> imports = node.imports();
		if (imports != null && !imports.isEmpty()) {
			if (imports.size() == 1) {
				// Single import - get the name directly
				ImportDeclaration importDecl = (ImportDeclaration) imports.get(0);
				Object importName = getNodeSyntax(importDecl.getName());
				if (importName != null) {
					children.add(importName);
				}
			} else {
				// Multiple imports - use getListOf as before
				Object importsNode = getListOf(imports);
				if (importsNode != null) {
					children.add(importsNode);
				}
			}
		}
		
		Object typesNode = getListOf(node.types());
		if (typesNode != null) {
			children.add(typesNode);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ConditionalExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ConstructorInvocation node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ContinueStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may re-implement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(CreationReference node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(Dimension node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(DoStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(EmptyStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(EnhancedForStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeBody = getNodeSyntax(node.getBody());
		if (nodeBody != null) {
			children.add(nodeBody);
		}
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
		Object nodeParameter = getNodeSyntax(node.getParameter());
		if (nodeParameter != null) {
			children.add(nodeParameter);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(EnumConstantDeclaration node) {
		syntaxMap = new HashMap<>();
		String content = node.getName().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(EnumDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		System.out.println("Yabba Dabba");
		Object nodeConstants = getListOf(node.enumConstants());
		if (nodeConstants != null) {
			children.add(nodeConstants);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(ExpressionMethodReference node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
			children.add(nodeName);
		}
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
		Object typeArguments = getListOf(node.typeArguments());
		if (typeArguments != null) {
			children.add(typeArguments);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ExpressionStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(FieldAccess node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
        Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
        Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
			children.add(nodeName);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(FieldDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeFragments = getListOf(node.fragments());
		if (nodeFragments != null) {
			children.add(nodeFragments);
		}
		Object nodeType = getNodeSyntax(node.getType());
		if (nodeType != null) {
			children.add(nodeType);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ForStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeBody = getNodeSyntax(node.getBody());
		if (nodeBody != null) {
			children.add(nodeBody);
		}
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
		Object nodeInitializers = getListOf(node.initializers());
		if (nodeInitializers != null) {
			children.add(nodeInitializers);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(IfStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
		Object nodeThenStatement = getNodeSyntax(node.getThenStatement());
		if (nodeThenStatement != null) {
			children.add(nodeThenStatement);
		}
		Object nodeElseStatement = getNodeSyntax(node.getElseStatement());
		if (nodeElseStatement != null) {
			children.add(nodeElseStatement);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ImportDeclaration node) {
		// Don't create a node for ImportDeclaration itself when called directly
		// Just return the name
		syntaxMap = (Map<String, Object>) getNodeSyntax(node.getName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(InfixExpression node) {
	    syntaxMap = new HashMap<>();
	    
	    createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
	    
	    ArrayList<Object> children = new ArrayList<>();
		Object leftOperand = getNodeSyntax(node.getLeftOperand());
	    if (leftOperand != null) {
	        children.add(leftOperand);
	    }
	    
	    Map<String, Object> operatorMap = new HashMap<>();
	    String operatorText = node.getOperator().toString();

	    String operatorPosition = getPositionString(node.getLeftOperand().getStartPosition() + node.getLeftOperand().getLength(), operatorText.length());
	    createAtom(operatorMap, operatorText, operatorPosition);
		syntaxMap.put("kind", "Normal");
	    children.add(operatorMap);
	    
		Object rightOperand = getNodeSyntax(node.getRightOperand());
	    if (rightOperand != null) {
	        children.add(rightOperand);
	    }
	    
	    if (!node.extendedOperands().isEmpty()) {
	        children.add(getListOf(node.extendedOperands()));
	    }
	    
	    syntaxMap.put("children", children);
	    
		syntaxMap.put("uniqueId", node.hashCode());
		
	    syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(Initializer node) {
		syntaxMap = new HashMap<>();
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object body = getNodeSyntax(node.getBody());
		if (body != null) {
			children.add(body);
		}
		syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(InstanceofExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(IntersectionType node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(LabeledStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(LambdaExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 * <p>Note: {@link LineComment} and {@link BlockComment} nodes are
	 * not considered part of main structure of the AST. This method will
	 * only be called if a client goes out of their way to visit this
	 * kind of node explicitly.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(LineComment node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(MarkerAnnotation node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object typeName = getNodeSyntax(node.getTypeName());
		if (typeName != null) {
			children.add(typeName);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(MemberRef node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(MemberValuePair node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(MethodRef node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(MethodRefParameter node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(MethodDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
			children.add(nodeName);
		}
		Object modifiers = getListOf(node.modifiers());
		if (modifiers != null) {
			children.add(modifiers);
		}
		Object parameters = getListOf(node.parameters());
		if (parameters != null) {
			children.add(parameters);
		}
		Object body = getNodeSyntax(node.getBody());
		if (body != null) {
			children.add(body);
		}
		Object returnType = getNodeSyntax(node.getReturnType2());
		if (returnType != null) {
			children.add(returnType);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(MethodInvocation node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "(", getPositionString(node.getStartPosition(), 1), ")", getPositionString(node.getStartPosition() + node.getLength(), 1));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
			children.add(nodeName);
		}
		Object arguments = getListOf(node.arguments());
		if (arguments != null) {
			children.add(arguments);
		}
		Object expression = getNodeSyntax(node.getExpression());
		if (expression != null) {
			children.add(expression);
		}
		Object typeArguments = getListOf(node.typeArguments());
		if (typeArguments != null) {
			children.add(typeArguments);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(Modifier node) {
		syntaxMap = new HashMap<>();
		String content = node.getKeyword().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Keyword");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(NameQualifiedType node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(NormalAnnotation node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(NullLiteral node) {
		syntaxMap = new HashMap<>();
		String content = "null";

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(NumberLiteral node) {
		syntaxMap = new HashMap<>();
		String content = node.getToken().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(PackageDeclaration node) {
		// Don't create a node for PackageDeclaration itself
		syntaxMap = (Map<String, Object>) getNodeSyntax(node.getName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(ParameterizedType node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeType = getNodeSyntax(node.getType());
		if (nodeType != null) {
			children.add(nodeType);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ParenthesizedExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(PostfixExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(PrefixExpression node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(PrimitiveType node) {
		syntaxMap = new HashMap<>();
		String content = node.getPrimitiveTypeCode().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Type");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(QualifiedName node) {
		syntaxMap = new HashMap<>();
		String content = node.getFullyQualifiedName();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(QualifiedType node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ReturnStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(SimpleName node) {
		syntaxMap = new HashMap<>();
		String content = node.getIdentifier();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(SimpleType node) {
		syntaxMap = new HashMap<>();
		String content = node.getName().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(SingleMemberAnnotation node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(SingleVariableDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
			children.add(nodeName);
		}
		Object nodeType = getNodeSyntax(node.getType());
		if (nodeType != null) {
			children.add(nodeType);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(StringLiteral node) {
		syntaxMap = new HashMap<>();
		String content = node.getLiteralValue();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "String(StringLiteral)");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(SuperConstructorInvocation node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeExpression = getNodeSyntax(node.getExpression());
		if (nodeExpression != null) {
			children.add(nodeExpression);
		}
		Object arguments = getListOf(node.arguments());
		if (arguments != null) {
			children.add(arguments);
		}
		Object typeArguments = getListOf(node.typeArguments());
		if (typeArguments != null) {
			children.add(typeArguments);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(SuperFieldAccess node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(SuperMethodInvocation node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(SuperMethodReference node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(SwitchCase node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(SwitchStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(SynchronizedStatement node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(TagElement node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.0
	 */
	public boolean visit(TextElement node) {
		throw new Error("Not Implemented!!!");
	}


	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ThisExpression node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeQualifier = getNodeSyntax(node.getQualifier());
		if (nodeQualifier != null) {
			children.add(nodeQualifier);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(ThrowStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(TryStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "{", getPositionString(node.getStartPosition(), 1), "}", getPositionString(node.getStartPosition() + node.getLength(), 1));
		
        ArrayList<Object> children = new ArrayList<>();
		Object catchClauses = getListOf(node.catchClauses());
		if (catchClauses != null) {
			children.add(catchClauses);
		}
		Object nodeBody = getNodeSyntax(node.getBody());
		if (nodeBody != null) {
			children.add(nodeBody);
		}
		Object nodeFinally = getNodeSyntax(node.getFinally());
		if (nodeFinally != null) {
			children.add(nodeFinally);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}
	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(TypeDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "{", getPositionString(node.getStartPosition(), 1), "}", getPositionString(node.getStartPosition() + node.getLength(), 1));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
			children.add(nodeName);
		}
		Object nodeFields = getListOf(Arrays.asList(node.getFields()));
		if (nodeFields != null) {
			children.add(nodeFields);
		}
		Object nodeMethods = getListOf(Arrays.asList(node.getMethods()));
		if (nodeMethods != null) {
			children.add(nodeMethods);
		}
		Object nodeTypes = getListOf(Arrays.asList(node.getTypes()));
		if (nodeTypes != null) {
			children.add(nodeTypes);
		}
		Object nodeSuper = getNodeSyntax(node.getSuperclassType());
		if (nodeSuper != null) {
			children.add(nodeSuper);
		}

        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(TypeDeclarationStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(TypeLiteral node) {
		syntaxMap = new HashMap<>();
		String content = node.getType().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Type");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.10
	 */
	public boolean visit(TypeMethodReference node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(TypeParameter node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.7.1
	 */
	public boolean visit(UnionType node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(VariableDeclarationExpression node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeType = getNodeSyntax(node.getType());
		if (nodeType != null) {
			children.add(nodeType);
		}
		Object nodeFragments = getListOf(node.fragments());
		if (nodeFragments != null) {
			children.add(nodeFragments);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(VariableDeclarationStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList<Object> children = new ArrayList<>();
		Object nodeType = getNodeSyntax(node.getType());
		if (nodeType != null) {
			children.add(nodeType);
		}
		Object nodeFragments = getListOf(node.fragments());
		if (nodeFragments != null) {
			children.add(nodeFragments);
		}
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
		syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 */
	public boolean visit(VariableDeclarationFragment node) {
	    syntaxMap = new HashMap<>();
	    
	    createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
	    
	    ArrayList<Object> children = new ArrayList<>();
		Object nodeName = getNodeSyntax(node.getName());
		if (nodeName != null) {
	        children.add(nodeName);
	    }
	    
	    if (node.getInitializer() != null) {
	        children.add(getNodeSyntax(node.getInitializer()));
	    }
	    
	    syntaxMap.put("children", children);
	    syntaxMap.put("uniqueId", node.hashCode());
	    
	    syntaxMap.put("nodeClass", node.getClass().getSimpleName());
		
		return false;
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(WhileStatement node) {
		throw new Error("Not Implemented!!!");
	}

	/**
	 * Visits the given type-specific AST node.
	 * <p>
	 * The default implementation does nothing and return true.
	 * Subclasses may reimplement.
	 * </p>
	 *
	 * @param node the node to visit
	 * @return <code>true</code> if the children of this node should be
	 * visited, and <code>false</code> if the children of this node should
	 * be skipped
	 * @since 3.1
	 */
	public boolean visit(WildcardType node) {
		throw new Error("Not Implemented!!!");
	}
}