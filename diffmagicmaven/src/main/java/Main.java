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
                        String usageId = generateId(node);
                        if (binding != null) {
                            String defId = binding.getKey();
                            nameResEdges.add(new Edge(usageId, defId));
                        } else {
                            nameResEdges.add(new Edge(usageId, "null"));
                        }
                        return super.visit(node);
                    }
                });

                // Collect AST structure and store as a tree (in the desired format)
                Map<String, Object> tree = new HashMap<>();
                tree.put("fileName", filePath);
                tree.put("node", "CompilationUnit");
                tree.put("id", generateId(compilationUnit));
                tree.put("location", compilationUnit.getStartPosition());

                // Collect package information
                PackageDeclaration packageDecl = compilationUnit.getPackage();
                if (packageDecl != null) {
                    Map<String, Object> packageData = new HashMap<>();
                    packageData.put("node", "PackageDeclaration");
                    packageData.put("id", generateId(packageDecl));
                    packageData.put("location", packageDecl.getStartPosition());
                    SimpleName packageName = (SimpleName) packageDecl.getName();
                    Map<String, Object> nameData = new HashMap<>();
                    nameData.put("node", "SimpleName");
                    nameData.put("id", generateId(packageName));
                    nameData.put("location", packageName.getStartPosition());
                    nameData.put("identifier", packageName.getIdentifier());
                    packageData.put("name", nameData);
                    tree.put("package", packageData);
                }

                // Collecting types and their details
                List<Map<String, Object>> types = new ArrayList<>();
                for (Object type : compilationUnit.types()) {
                    TypeDeclaration typeDecl = (TypeDeclaration) type;
                    Map<String, Object> typeData = collectTypeDetails(typeDecl);
                    types.add(typeData);
                }
                tree.put("types", types);

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

    // Generate a unique ID for a node (usage)
    public static String generateId(ASTNode node) {
        return node.getClass().getSimpleName() + "@" + node.getStartPosition();
    }

    // Collect details of a type declaration
    public static Map<String, Object> collectTypeDetails(TypeDeclaration typeDecl) {
        Map<String, Object> typeData = new HashMap<>();
        typeData.put("node", "TypeDeclaration");
        typeData.put("id", generateId(typeDecl));
        typeData.put("location", typeDecl.getStartPosition());
        typeData.put("modifiers", collectModifiers(typeDecl.modifiers()));

        // Collect type name
        SimpleName typeName = typeDecl.getName();
        Map<String, Object> nameData = new HashMap<>();
        nameData.put("node", "SimpleName");
        nameData.put("id", generateId(typeName));
        nameData.put("location", typeName.getStartPosition());
        nameData.put("identifier", typeName.getIdentifier());
        typeData.put("name", nameData);

        // Collect methods
        List<Map<String, Object>> methods = new ArrayList<>();
        for (MethodDeclaration method : typeDecl.getMethods()) {
            Map<String, Object> methodData = collectMethodDetails(method);
            methods.add(methodData);
        }
        typeData.put("methods", methods);

        return typeData;
    }

    // Collect details of a method declaration
    public static Map<String, Object> collectMethodDetails(MethodDeclaration methodDecl) {
        Map<String, Object> methodData = new HashMap<>();
        methodData.put("node", "MethodDeclaration");
        methodData.put("id", generateId(methodDecl));
        methodData.put("location", methodDecl.getStartPosition());
        methodData.put("name", methodDecl.getName().getIdentifier());
        return methodData;
    }

    // Collect modifiers
    public static List<Map<String, Object>> collectModifiers(List<?> modifiers) {
        List<Map<String, Object>> modifierData = new ArrayList<>();
        for (Object mod : modifiers) {
            Modifier modifier = (Modifier) mod;
            Map<String, Object> modData = new HashMap<>();
            modData.put("node", "Modifier");
            modData.put("id", generateId(modifier));
            modData.put("location", modifier.getStartPosition());
            modData.put("keyword", modifier.getKeyword().toString());
            modifierData.add(modData);
        }
        return modifierData;
    }
}
