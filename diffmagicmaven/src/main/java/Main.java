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
            // Directory containing Java files
            String sourceDir = "C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\Files\\Java";

            List<String> javaFiles = getAllJavaFiles(new File(sourceDir));

            List<char[]> sources = new ArrayList<>();
            for (String filePath : javaFiles) {
                sources.add(new String(Files.readAllBytes(Paths.get(filePath))).toCharArray());
            }

            // Classpath setup
            String[] classpathEntries = { System.getProperty("java.home") + "\\lib\\rt.jar" }; // May need to adust based on JDK version
            String[] sourcepathEntries = { sourceDir }; // Sourcepath includes all Java files

            List<FileDetails> filesDetails = new ArrayList<>();
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
                
                // Processing each file:

                Statement stmt = (Statement)((TypeDeclaration)compilationUnit.types().getFirst()).getMethods()[0].getBody().statements().getFirst();

                JsonVisitor jsonVisitor = new JsonVisitor(compilationUnit);
                //compilationUnit.accept(jsonVisitor);
                stmt.accept(jsonVisitor);
                Map<String, Object> tree = jsonVisitor.jsonMap;
                
                SyntaxVisitor syntaxVisitor = new SyntaxVisitor(compilationUnit, source);
                //compilationUnit.accept(syntaxVisitor);
                stmt.accept(syntaxVisitor);
                Map<String, Object> syntax = syntaxVisitor.syntaxMap;
                
                filesDetails.add(new FileDetails(filePath, tree, nameResEdges, syntax));
            }

            // Prepare JSON output
            Map<String, Object> jsonOutput = new HashMap<>();
            List<Map<String, Object>> trees = new ArrayList<>();
            List<Edge> allNameResEdges = new ArrayList<>();
            List<Map<String, Object>> syntaxes = new ArrayList<>();

            for (FileDetails fileDetails : filesDetails) {
                trees.add(fileDetails.tree);
                allNameResEdges.addAll(fileDetails.nameResEdges);
                syntaxes.add(fileDetails.syntax);
            }

            // Add edges, trees and syntax to the JSON output
            jsonOutput.put("nameResEdges", allNameResEdges);
            jsonOutput.put("trees", trees);
            jsonOutput.put("syntax", syntaxes);

            // Convert to JSON with pretty printing
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonStr = gson.toJson(jsonOutput);
            
            // Convert to JSONObject for content_id processing
            JSONObject jsonObject = new JSONObject(jsonStr);

            // Update num_descendants values in the syntax tree
            JsonTraverser.updateNumDescendants(jsonObject);

            // Generate and add syntax info
            JSONArray syntaxInfo = JsonTraverser.generateSyntaxInfo(jsonObject);
            jsonObject.put("syntaxInfo", syntaxInfo);

            // Output the updated JSON with syntaxInfo
            System.out.println(jsonObject.toString(2));

        } catch (Exception e) {
            e.printStackTrace();
        }
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