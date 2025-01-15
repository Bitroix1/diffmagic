import org.eclipse.jdt.core.dom.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    // Class to represent edges in the name resolution graph
    static class Edge {
        String from;
        String to;

        public Edge(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    // Class to represent AST nodes
    static class ASTNodeDetails {
        String node;
        String id;
        int location;
        Map<String, Object> additionalData = new HashMap<>();
    }

    // Class to represent a single file's AST and name resolution information
    static class FileDetails {
        String fileName;
        Map<String, Object> tree;
        List<Edge> nameResEdges;

        public FileDetails(String fileName, Map<String, Object> tree, List<Edge> nameResEdges) {
            this.fileName = fileName;
            this.tree = tree;
            this.nameResEdges = nameResEdges;
        }
    }

    public static void main(String[] args) {
        try {
            // Directory containing Java files
            String sourceDir = "C:\\Users\\Public\\TestFolder\\1\\try";

            // Get a list of all Java files in the directory
            List<String> javaFiles = getAllJavaFiles(new File(sourceDir));

            // Collect the source code for each file
            List<char[]> sources = new ArrayList<>();
            for (String filePath : javaFiles) {
                sources.add(new String(Files.readAllBytes(Paths.get(filePath))).toCharArray());
            }

            // Classpath setup
            String[] classpathEntries = { System.getProperty("java.home") + "\\lib\\rt.jar" }; // Adjust for your JDK
            String[] sourcepathEntries = { sourceDir }; // Sourcepath includes all Java files

            // List to store the details of each file (AST and IBinding info)
            List<FileDetails> filesDetails = new ArrayList<>();

            // Process each Java file
            for (int i = 0; i < javaFiles.size(); i++) {
                String filePath = javaFiles.get(i);
                char[] source = sources.get(i);

                // Create and configure the parser
                ASTParser parser = ASTParser.newParser(AST.JLS8);
                parser.setKind(ASTParser.K_COMPILATION_UNIT);
                parser.setResolveBindings(true); // Enable binding resolution
                parser.setBindingsRecovery(true); // Recover bindings if possible
                parser.setEnvironment(classpathEntries, sourcepathEntries, new String[] { "UTF-8" }, true);
                parser.setUnitName(new File(filePath).getName());
                parser.setSource(source);

                // Parse the source and get the compilation unit
                CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(null);

                // List to store name resolution edges for this file
                List<Edge> nameResEdges = new ArrayList<>();

                // Visit the AST and collect bindings
                compilationUnit.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName node) {
                        IBinding binding = node.resolveBinding();
                        String usageId = JsonVisitor.generateId(node);
                        if (binding != null) {
                            String defId = binding.getKey();
                            nameResEdges.add(new Edge(usageId, defId));
                        } else {
                            nameResEdges.add(new Edge(usageId, "null"));
                        }
                        return super.visit(node);
                    }
                });
                
                JsonVisitor jsonVisitor = new JsonVisitor(compilationUnit);
                compilationUnit.accept(jsonVisitor);

                // Collect AST structure and store as a tree (in the desired format)
                Map<String, Object> tree = jsonVisitor.jsonMap;
                
                // Store the result for this file
                filesDetails.add(new FileDetails(filePath, tree, nameResEdges));
            }

            // Prepare JSON output
            Map<String, Object> jsonOutput = new HashMap<>();
            List<Map<String, Object>> trees = new ArrayList<>();
            List<Edge> allNameResEdges = new ArrayList<>();
            for (FileDetails fileDetails : filesDetails) {
                trees.add(fileDetails.tree);
                allNameResEdges.addAll(fileDetails.nameResEdges);
            }

            // Add trees and edges to the JSON output
            jsonOutput.put("trees", trees);
            jsonOutput.put("nameResEdges", allNameResEdges);

            // Convert to JSON with pretty printing
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(jsonOutput);
            System.out.println(json);

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