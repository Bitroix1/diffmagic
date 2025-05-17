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
    
    SyntaxVisitor(CompilationUnit cu, char[] source) {
        this.cu = cu;
        this.source = source;
    }
	
    private int getUniqueId(String content) {
        return uniqueId++;
    }
    
    // Create an Atom element for the syntax tree
    private void createAtom(Map<String, Object> nodeMap, String content, String position) {
        nodeMap.put("node_kind", "atom");
        nodeMap.put("id", getUniqueId(content));
        nodeMap.put("content_id", 0); // will be fixed later
        nodeMap.put("content", content);
        nodeMap.put("position", position);
    }
    
    // Create a List element for the syntax tree
    private void createList(Map<String, Object> nodeMap, String openContent, String openPosition, String closeContent, String closePosition) {
        nodeMap.put("node_kind", "list");
        nodeMap.put("id", getUniqueId(openContent));
        nodeMap.put("content_id", 0); // will be fixed later
        nodeMap.put("open_content", openContent);
        nodeMap.put("open_position", openPosition);
        nodeMap.put("close_content", closeContent);
        nodeMap.put("close_position", closePosition);
    }
    
    private Object getNodeSyntax(ASTNode node) {
		if (node == null) {
	        return "null";
	    }
		
		SyntaxVisitor visitor = new SyntaxVisitor(this.cu, this.source);
		node.accept(visitor);
		return visitor.syntaxMap == null ? node.getClass().getSimpleName() + " IS NOT IMPLEMENTED!!!" : visitor.syntaxMap;
	}
	
	private Object getListOf(List list) {
		List<Object> objectList = new ArrayList<>();
		if (list == null || list.isEmpty()) {
			return null; // Return null instead of empty list
		}
		
		for (Object object : list) {
			if (object instanceof ASTNode) {
				ASTNode node = (ASTNode)object;
				Object result = getNodeSyntax(node);
				// Only add non-null results
				if (result != null) {
					objectList.add(result);
				}
			}
		}
		
		// Return null if we end up with an empty list after processing
		return objectList.isEmpty() ? null : objectList;
	}

    // Get position as a string in format "line:column-endColumn"
    private String getPositionString(int startPosition, int length) {
        if (startPosition < 0 || length <= 0) {
            return "0:0-0";
        }
        int startLine = cu.getLineNumber(startPosition);
        int startCol = cu.getColumnNumber(startPosition);
        int endCol = startCol + length;
        
        return startLine + ":" + startCol + "-" + endCol;
    }
    
    // Extract string content from source for a node
    private String getSourceContent(ASTNode node) {
        try {
            int start = node.getStartPosition();
            int length = node.getLength();
            
            if (start < 0 || length <= 0 || start + length > source.length) {
                return "";
            }
            
            return new String(source, start, length);
        } catch (Exception e) {
            return "";
        }
    }
    
    // Find the position of an opening brace in source code
    private int findOpeningBracePosition(ASTNode node) {
        int startSearchFrom = node.getStartPosition();
        
        for (int i = startSearchFrom; i < node.getStartPosition() + node.getLength(); i++) {
            if (i < source.length && source[i] == '{') {
                return i;
            }
        }
        
        return node.getStartPosition() + 1;
    }
    
    // Find the keyword offset within a node
    private int getKeywordOffset(ASTNode node, String keyword) {
        String src = getSourceContent(node);
        int index = src.indexOf(keyword);
        return index >= 0 ? index : 0;
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
	public boolean visit(AnnotationTypeMemberDeclaration node) {
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
	public boolean visit(AnonymousClassDeclaration node) {
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
	public boolean visit(ArrayAccess node) {
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
	public boolean visit(ArrayCreation node) {
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
	public boolean visit(ArrayInitializer node) {
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
	public boolean visit(ArrayType node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getElementType()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());

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
	public boolean visit(Assignment node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getLeftHandSide()));
        children.add(getNodeSyntax(node.getRightHandSide()));
        
        Map<String, Object> operatorMap = new HashMap<>();
	    String operatorText = node.getOperator().toString();

	    String operatorPosition = getPositionString(node.getLeftHandSide().getStartPosition() + node.getRightHandSide().getLength(), operatorText.length());
	    createAtom(operatorMap, operatorText, operatorPosition);
		syntaxMap.put("kind", "Normal");
	    children.add(operatorMap);
        
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());

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
		
		createList(syntaxMap, "{", getPositionString(node.getStartPosition(), 1), "}", getPositionString(node.getStartPosition() + node.getLength(), 1));
		
		ArrayList children = new ArrayList<>();
        children.add(getListOf(node.statements()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());

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
	public boolean visit(BooleanLiteral node) {
		syntaxMap = new HashMap<>();
		String content = Boolean.toString(node.booleanValue());

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(CastExpression node) {
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
	public boolean visit(CatchClause node) {
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getPackage()));
        children.add(getListOf(node.imports()));
        children.add(getListOf(node.types()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(ConstructorInvocation node) {
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
	public boolean visit(ContinueStatement node) {
		return false;
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
	public boolean visit(Dimension node) {
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
	public boolean visit(DoStatement node) {
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
	public boolean visit(EmptyStatement node) {
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
	public boolean visit(EnhancedForStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getBody()));
        children.add(getNodeSyntax(node.getExpression()));
        children.add(getNodeSyntax(node.getParameter()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		return false;
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
	public boolean visit(ExpressionMethodReference node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        children.add(getNodeSyntax(node.getExpression()));
        children.add(getListOf(node.typeArguments()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getExpression()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getBody()));
        children.add(getNodeSyntax(node.getExpression()));
        children.add(getListOf(node.initializers()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getExpression()));
        children.add(getNodeSyntax(node.getElseStatement()));
        children.add(getNodeSyntax(node.getThenStatement()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	    
	    ArrayList children = new ArrayList<>();
	    children.add(getNodeSyntax(node.getLeftOperand()));
	    
	    Map<String, Object> operatorMap = new HashMap<>();
	    String operatorText = node.getOperator().toString();

	    String operatorPosition = getPositionString(node.getLeftOperand().getStartPosition() + node.getLeftOperand().getLength(), operatorText.length());
	    createAtom(operatorMap, operatorText, operatorPosition);
		syntaxMap.put("kind", "Normal");
	    children.add(operatorMap);
	    
	    children.add(getNodeSyntax(node.getRightOperand()));
	    
	    if (!node.extendedOperands().isEmpty()) {
	        children.add(getListOf(node.extendedOperands()));
	    }
	    
	    syntaxMap.put("children", children);
	    
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(IntersectionType node) {
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
	public boolean visit(LabeledStatement node) {
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
	public boolean visit(LambdaExpression node) {
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
	public boolean visit(LineComment node) {
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
	public boolean visit(MarkerAnnotation node) {
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
	public boolean visit(MemberValuePair node) {
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
	public boolean visit(MethodRef node) {
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
	public boolean visit(MethodRefParameter node) {
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
	public boolean visit(MethodDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        children.add(getListOf(node.modifiers()));
        children.add(getListOf(node.parameters()));
        children.add(getNodeSyntax(node.getBody()));
        children.add(getNodeSyntax(node.getReturnType2()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        children.add(getListOf(node.arguments()));
        children.add(getNodeSyntax(node.getExpression()));
        children.add(getListOf(node.typeArguments()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(NormalAnnotation node) {
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
	public boolean visit(NullLiteral node) {
		syntaxMap = new HashMap<>();
		String content = "null";

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getType()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(PostfixExpression node) {
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
	public boolean visit(PrefixExpression node) {
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
	public boolean visit(PrimitiveType node) {
		syntaxMap = new HashMap<>();
		String content = node.getPrimitiveTypeCode().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Type");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getQualifier()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(ReturnStatement node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getExpression()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(SimpleName node) {
		syntaxMap = new HashMap<>();
		String content = node.getIdentifier();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Normal");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(SingleVariableDeclaration node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        children.add(getNodeSyntax(node.getType()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(SuperMethodInvocation node) {
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
	public boolean visit(SuperMethodReference node) {
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
	public boolean visit(SwitchCase node) {
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
	public boolean visit(SwitchStatement node) {
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
	public boolean visit(SynchronizedStatement node) {
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
	public boolean visit(TagElement node) {
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
	public boolean visit(TextElement node) {
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
	public boolean visit(ThisExpression node) {
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
	public boolean visit(TryStatement node) {
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getName()));
        children.add(getListOf(Arrays.asList(node.getFields())));
        children.add(getListOf(Arrays.asList(node.getMethods())));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	public boolean visit(TypeLiteral node) {
		syntaxMap = new HashMap<>();
		String content = node.getType().toString();

		createAtom(syntaxMap, content, getPositionString(node.getStartPosition(), content.length()));
		syntaxMap.put("kind", "Type");
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	 * 
	 * @since 3.10
	 */
	public boolean visit(TypeMethodReference node) {
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
	public boolean visit(TypeParameter node) {
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
	 * @since 3.7.1
	 */
	public boolean visit(UnionType node) {
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
	public boolean visit(VariableDeclarationExpression node) {
		syntaxMap = new HashMap<>();
		
		createList(syntaxMap, "", getPositionString(node.getStartPosition(), 0), "", getPositionString(node.getStartPosition() + node.getLength(), 0));
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getType()));
        children.add(getListOf(node.fragments()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
		
        ArrayList children = new ArrayList<>();
        children.add(getNodeSyntax(node.getType()));
        children.add(getListOf(node.fragments()));
        syntaxMap.put("children", children);
		
		syntaxMap.put("uniqueId", node.hashCode());
		
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
	    
	    ArrayList children = new ArrayList<>();
	    children.add(getNodeSyntax(node.getName()));
	    
	    if (node.getInitializer() != null) {
	        children.add(getNodeSyntax(node.getInitializer()));
	    }
	    
	    syntaxMap.put("children", children);
	    syntaxMap.put("uniqueId", node.hashCode());
	    
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
	public boolean visit(WhileStatement node) {
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
	public boolean visit(WildcardType node) {
		return false;
	}
}