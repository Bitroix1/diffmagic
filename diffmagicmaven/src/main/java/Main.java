import org.eclipse.jdt.core.dom.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
        List<Edge> nameResEdges;
        Map<String, Object> syntax;

        public FileDetails(String fileName, List<Edge> nameResEdges, Map<String, Object> syntax) {
            this.fileName = fileName;
            this.nameResEdges = nameResEdges;
            this.syntax = syntax;
        }
    }

    public static void main(String[] args) {
        try {
            // Two directories for LHS and RHS comparison
            String lhsSourceDir = "C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\difftastic\\Files\\lhs";
            String rhsSourceDir = "C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\difftastic\\Files\\rhs";

            // Process LHS files
            List<String> lhsJavaFiles = getAllJavaFiles(new File(lhsSourceDir));
            List<FileDetails> lhsFilesDetails = processFiles(lhsJavaFiles, 1);

            // Output LHS data
            Map<String, Object> lhsOutput = new HashMap<>();
            
            List<Edge> lhsNameResEdges = new ArrayList<>();
            Map<String, Object> lhsSyntaxes = new HashMap<>();

            // Collect data from processed files
            for (FileDetails fileDetails : lhsFilesDetails) {
                lhsNameResEdges.addAll(fileDetails.nameResEdges);
                
                String fileName = new File(fileDetails.fileName).getName();
                lhsSyntaxes.put(fileName, fileDetails.syntax);
            }

            lhsOutput.put("nameResEdges", lhsNameResEdges);
            lhsOutput.put("syntax", lhsSyntaxes);

            // Convert LHS to JSON with pretty printing
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String lhsJsonStr = gson.toJson(lhsOutput);

            // Output the LHS JSON
            System.out.println("LHS OUTPUT:");
            System.out.println(lhsJsonStr);

            // Save LHS JSON to file
            try (FileWriter file = new FileWriter("C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\difftastic\\Files\\lhs.json")) {
                file.write(lhsJsonStr);
                System.out.println("LHS JSON saved to: C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\difftastic\\Files\\lhs.json");
            } catch (IOException e) {
                System.err.println("Error saving LHS JSON to file: " + e.getMessage());
            }

            int lastLhsUniqueId = SyntaxVisitor.getLastUniqueId();

            // Process RHS files
            List<String> rhsJavaFiles = getAllJavaFiles(new File(rhsSourceDir));
            List<FileDetails> rhsFilesDetails = processFiles(rhsJavaFiles, lastLhsUniqueId + 1);

            // Output RHS data
            Map<String, Object> rhsOutput = new HashMap<>();
            
            List<Edge> rhsNameResEdges = new ArrayList<>();
            Map<String, Object> rhsSyntaxes = new HashMap<>();

            // Collect data from processed files
            for (FileDetails fileDetails : rhsFilesDetails) {
                rhsNameResEdges.addAll(fileDetails.nameResEdges);
                
                String fileName = new File(fileDetails.fileName).getName();
                rhsSyntaxes.put(fileName, fileDetails.syntax);
            }

            rhsOutput.put("nameResEdges", rhsNameResEdges);
            rhsOutput.put("syntax", rhsSyntaxes);

            // Convert RHS to JSON with pretty printing
            String rhsJsonStr = gson.toJson(rhsOutput);

            // Output the RHS JSON
            System.out.println("RHS OUTPUT:");
            System.out.println(rhsJsonStr);

            // Save RHS JSON to file
            try (FileWriter file = new FileWriter("C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\difftastic\\Files\\rhs.json")) {
                file.write(rhsJsonStr);
                System.out.println("RHS JSON saved to: C:\\Users\\Bitroix\\Desktop\\Technion\\Diff\\difftastic\\Files\\rhs.json");
            } catch (IOException e) {
                System.err.println("Error saving RHS JSON to file: " + e.getMessage());
            }

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
    
    // Helper method to process files
    private static List<FileDetails> processFiles(
            List<String> javaFiles,
            int startUniqueId) throws Exception {
        List<FileDetails> filesDetails = new ArrayList<>();
        List<char[]> sources = new ArrayList<>();

        for (String filePath : javaFiles) {
            sources.add(new String(Files.readAllBytes(Paths.get(filePath))).toCharArray());
        }

        // Classpath setup
        String[] classpathEntries = { System.getProperty("java.home") + "\\lib\\rt.jar" };
        String[] sourcepathEntries = { new File(javaFiles.get(0)).getParent() };

        SyntaxVisitor.setStartingUniqueId(startUniqueId);

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
            
            // Visit the AST to collect bindings
            List<Edge> nameResEdges = new ArrayList<>();
            compilationUnit.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    IBinding binding = node.resolveBinding();
                    String usageId = node.getClass().getSimpleName() + "@" + node.getStartPosition();
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

            // Create SyntaxVisitor with the content ID cache if available
            SyntaxVisitor syntaxVisitor;
            syntaxVisitor = new SyntaxVisitor(compilationUnit, source);
            root.accept(syntaxVisitor);
            Map<String, Object> syntax = syntaxVisitor.syntaxMap;

            filesDetails.add(new FileDetails(filePath, nameResEdges, syntax));
        }

        return filesDetails;
    }
}