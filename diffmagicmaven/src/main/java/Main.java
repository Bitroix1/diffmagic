import org.eclipse.jdt.core.dom.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    // Edges in the name resolution graph
    static class Edge {
        String from;
        String to;
        int fromId;
        int toId;  // May be -1 if binding doesn't have a corresponding node

        public Edge(String from, String to, int fromId, int toId) {
            this.from = from;
            this.to = to;
            this.fromId = fromId;
            this.toId = toId;
        }
    }

    static class FileDetails {
        String fileName;
        Map<String, Object> tree;
        List<Edge> nameResEdges;
        Map<String, Object> syntax;

        public FileDetails(String fileName, Map<String, Object> tree, List<Edge> nameResEdges, Map<String, Object> syntax) {
            this.fileName = fileName;
            this.tree = tree;
            this.nameResEdges = nameResEdges;
            this.syntax = syntax;
        }
    }

    public static void main(String[] args) {
        try {
            // Two directories for LHS and RHS comparison
            String lhsSourceDir = "C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\Files\\lhs";
            String rhsSourceDir = "C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\Files\\rhs";

            // Process LHS files
            List<String> lhsJavaFiles = getAllJavaFiles(new File(lhsSourceDir));
            List<FileDetails> lhsFilesDetails = processFiles(lhsJavaFiles, null, null, 1);
            
            // Output LHS data
            Map<String, Object> lhsOutput = new HashMap<>();
            List<Map<String, Object>> lhsTrees = new ArrayList<>();
            List<Edge> lhsNameResEdges = new ArrayList<>();
            List<Map<String, Object>> lhsSyntaxes = new ArrayList<>();
            
            for (FileDetails fileDetails : lhsFilesDetails) {
                lhsTrees.add(fileDetails.tree);
                lhsNameResEdges.addAll(fileDetails.nameResEdges);
                lhsSyntaxes.add(fileDetails.syntax);
            }
            
            lhsOutput.put("nameResEdges", lhsNameResEdges);
            lhsOutput.put("trees", lhsTrees);
            lhsOutput.put("syntax", lhsSyntaxes);
            
            // Convert LHS to JSON with pretty printing
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String lhsJsonStr = gson.toJson(lhsOutput);
            
            // Convert to JSONObject for content_id processing
            JSONObject lhsJsonObject = new JSONObject(lhsJsonStr);
            
            // Update num_descendants values in the syntax tree
            JsonTraverser.updateNumDescendants(lhsJsonObject);
            
            // Generate and add syntax info
            JSONArray lhsSyntaxInfo = JsonTraverser.generateSyntaxInfo(lhsJsonObject);
            lhsJsonObject.put("syntaxInfo", lhsSyntaxInfo);
            
            // Output the LHS JSON
            System.out.println("LHS OUTPUT:");
            System.out.println(lhsJsonObject.toString(2)); // Can use .getJSONArray("syntax")
            
            // Get the last uniqueId used for LHS processing
            int lastLhsUniqueId = SyntaxVisitor.getLastUniqueId();
            
            // Get the content ID cache and list ID cache from JsonTraverser
            Map<String, Integer> contentIdCache = JsonTraverser.getContentIdCache();
            Map<Integer, Integer> listIdCache = JsonTraverser.getListIdCache();

            // Process RHS files, starting from last LHS uniqueId + 1
            List<String> rhsJavaFiles = getAllJavaFiles(new File(rhsSourceDir));
            List<FileDetails> rhsFilesDetails = processFiles(rhsJavaFiles, contentIdCache, listIdCache, lastLhsUniqueId + 1);
            
            // Output RHS data
            Map<String, Object> rhsOutput = new HashMap<>();
            List<Map<String, Object>> rhsTrees = new ArrayList<>();
            List<Edge> rhsNameResEdges = new ArrayList<>();
            List<Map<String, Object>> rhsSyntaxes = new ArrayList<>();
            
            for (FileDetails fileDetails : rhsFilesDetails) {
                rhsTrees.add(fileDetails.tree);
                rhsNameResEdges.addAll(fileDetails.nameResEdges);
                rhsSyntaxes.add(fileDetails.syntax);
            }
            
            rhsOutput.put("nameResEdges", rhsNameResEdges);
            rhsOutput.put("trees", rhsTrees);
            rhsOutput.put("syntax", rhsSyntaxes);
            
            // Convert RHS to JSON with pretty printing
            String rhsJsonStr = gson.toJson(rhsOutput);
            
            // Convert to JSONObject for content_id processing
            JSONObject rhsJsonObject = new JSONObject(rhsJsonStr);
            
            // Update num_descendants values in the syntax tree
            JsonTraverser.updateNumDescendants(rhsJsonObject);
            
            // Generate and add syntax info
            JSONArray rhsSyntaxInfo = JsonTraverser.generateSyntaxInfo(rhsJsonObject);
            rhsJsonObject.put("syntaxInfo", rhsSyntaxInfo);
            
            // Output the RHS JSON
            System.out.println("\nRHS OUTPUT:");
            System.out.println(rhsJsonObject.toString(2));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Helper method to process files
    private static List<FileDetails> processFiles(
            List<String> javaFiles, 
            Map<String, Integer> contentIdCache,
            Map<Integer, Integer> listIdCache, // Add this parameter
            int startUniqueId) throws Exception {
        List<FileDetails> filesDetails = new ArrayList<>();
        List<char[]> sources = new ArrayList<>();
        
        for (String filePath : javaFiles) {
            sources.add(new String(Files.readAllBytes(Paths.get(filePath))).toCharArray());
        }

        // Classpath setup
        String[] classpathEntries = { System.getProperty("java.home") + "\\lib\\rt.jar" };
        String[] sourcepathEntries = { new File(javaFiles.get(0)).getParent() };

        // Set the starting uniqueId for SyntaxVisitor
        if (startUniqueId > 1) {
            SyntaxVisitor.setStartingUniqueId(startUniqueId);
        }

        for (int i = 0; i < javaFiles.size(); i++) {
            String filePath = javaFiles.get(i);
            char[] source = sources.get(i);

            // Create and configure the parser
            ASTParser parser = ASTParser.newParser(AST.JLS8);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setEnvironment(classpathEntries, sourcepathEntries, new String[] { "UTF-8" }, true);
            parser.setUnitName(new File(filePath).getName());
            parser.setSource(source);

            CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(null);
            List<Edge> nameResEdges = new ArrayList<>();

            // Visit the AST to collect bindings
            compilationUnit.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    IBinding binding = node.resolveBinding();
                    String usageId = JsonVisitor.generateId(node);
                    int usageIdNum = node.hashCode();
                    
                    if (binding != null) {
                        String defId = binding.getKey();
                        int defIdNum = 0;

                        if (binding.getJavaElement() != null) {
                            defIdNum = binding.getJavaElement().hashCode();
                        } else {
                            // Fallback to using the binding's hashCode
                            defIdNum = binding.hashCode();
                        }

                        nameResEdges.add(new Edge(usageId, defId, usageIdNum, defIdNum));
                    } else {
                        nameResEdges.add(new Edge(usageId, "null", usageIdNum, -1));
                    }
                    return super.visit(node);
                }
            });
            
            // Start node- default should be compilationUnit
            CompilationUnit root = compilationUnit;
            
            JsonVisitor jsonVisitor = new JsonVisitor(compilationUnit);
            root.accept(jsonVisitor);
            Map<String, Object> tree = jsonVisitor.jsonMap;
            
            // Create SyntaxVisitor with the content ID cache if available
            SyntaxVisitor syntaxVisitor;
            if (contentIdCache != null) {
                syntaxVisitor = new SyntaxVisitor(compilationUnit, source, contentIdCache, listIdCache);
            } else {
                syntaxVisitor = new SyntaxVisitor(compilationUnit, source);
            }
            
            root.accept(syntaxVisitor);
            Map<String, Object> syntax = syntaxVisitor.syntaxMap;
            
            filesDetails.add(new FileDetails(filePath, tree, nameResEdges, syntax));
        }

        return filesDetails;
    }

    // Recursively collect all .java files in a directory
    public static List<String> getAllJavaFiles(File directory) {
        List<String> javaFiles = new ArrayList<>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                javaFiles.addAll(getAllJavaFiles(file));
            } else if (file.getName().endsWith(".java")) {
                javaFiles.add(file.getAbsolutePath());
            }
        }
        return javaFiles;
    }
}