package nextflow.dag

import nextflow.Session
import nextflow.processor.TaskId
import nextflow.trace.TraceRecord
import groovy.util.logging.Slf4j
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.nio.file.Path


@Slf4j
/**
 * Renders the DAG in .dax Format representing the Workflow.
 * Renders the minimal DAG in .dax Format for simulations in Wrench (https://wrench-project.org/)
 * @author Frederic Risling
 */
class DAXRenderer implements DagRenderer {

    /**
     * The current DAG
     */
    private DAG dag

    /**
     * All the trace records of the current execution.
     * Contains the runtime for the tasks
     */
    private Map<TaskId, TraceRecord> records

    /**
     * The path where the DAG will be saved
     */
    private Path path

    /**
     * the name of the executed workflow
     */
    private String namespace

    /**
     * the current session for this workflow
     */
    private Session session

    /**
     * the list of input and output files of all executed tasks
     */
    private List<FileDependency> files

    /**
     * helper Map to avoid duplicate output file-names in the .dax file
     */
    private Map<String, Integer> duplicates


    /**
     * Finals for creating the header information in the .dax file
     */
    private static final String XMLNS = "http://pegasus.isi.edu/schema/DAX"
    private static final String XMLNS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private static final String XSI_LOCATION = XMLNS + " http://pegasus.isi.edu/schema/dax-2.1.xsd"
    private static final String VERSION = "2.1"

    /**
     * Constructor of a DAXRenderer
     * @param records
     * @param session
     */
    DAXRenderer(Map<TaskId, TraceRecord> records, Session session) {
        this.records = records
        this.session = session
        this.namespace = generateNamespace()
        this.files = new ArrayList<FileDependency>()
        this.duplicates = new HashMap<String, Integer>()
    }

    /**
     * Overrides the renderDocument() method of the DagRenderer Interface
     * @param dag
     * @param file
     */
    void renderDocument(DAG dag, Path file) {
        this.dag = dag
        this.path = file
        renderDAX()
    }

    /**
     * creates a <filename>.dax representation in the current directory
     */
    void renderDAX() {
        //Create .dax file Header and meta information
        final Charset charset = Charset.defaultCharset()
        Writer bw = Files.newBufferedWriter(this.path, charset)
        final XMLOutputFactory xof = XMLOutputFactory.newFactory()
        final XMLStreamWriter w = xof.createXMLStreamWriter(bw)
        w.writeStartDocument(charset.displayName(), "1.0")
        w.writeComment(" generated: " + new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime()))
        w.writeComment(" generated by: " + System.getProperty("user.name") + " ")
        w.writeCharacters("\n\n")
        w.writeStartElement("adag")
        w.writeAttribute("xmlns", XMLNS)
        w.writeAttribute("xmlns:xsi", XMLNS_XSI)
        w.writeAttribute("xsi:schemaLocation", XSI_LOCATION)
        w.writeAttribute("version", VERSION)
        //TODO: Attribut count
        //TODO: Attribut index
        //TODO: Attribut name
        //TODO: Attribut jobCount
        //TODO: Attribut fileCount
        //TODO: Attribut childCount


        //Part 1: List of all referenced files
        def edges = dag.edges
        w.writeCharacters("\n")
        w.writeComment(" part 1: list of all referenced files (may be empty) ")
        def refFiles = edges.stream()
                .filter(edge -> edge.from != null)
                .filter(edge -> edge.from.type == DAG.Type.ORIGIN)
                .map(edge -> edge.from.label.toString())
                .distinct()
                .toArray()
        for (file in refFiles) {
            if (file.toString() == "null") {
                break
            }
            w.writeCharacters("\n")
            w.writeStartElement("file")
            w.writeAttribute("name", file.toString())
            w.writeEndElement()
        }

        //Part 2: List of all executed Jobs
        w.writeCharacters("\n")
        w.writeComment(" part 2: definition of all jobs (at least one) ")

        //map entries are not sorted so first we have to sort them

        for (record in records) {
            //add files to file list
            addFilesForRecord(record.value)
            //<job>-element + attributes
            w.writeStartElement("job")
            String id = record.value.get("task_id").toString()
            w.writeAttribute("id", id)
            w.writeAttribute("namespace", namespace)
            String name = record.value.get("name")
            w.writeAttribute("name", name)
            double realtime = record.value.get("duration") / 1000

            //log.info("Realtime: " + record.value.get("realtime").toString())
            //log.info("Duration: " + record.value.get("duration").toString())

            //fix for 1-core jobs
            if(record.value.get("cpus").toString()=="1"){
                w.writeAttribute("runtime", Double.toString(realtime/2))
                w.writeAttribute("runtime_raw", Double.toString(realtime))
            }
            else{
                w.writeAttribute("runtime", Double.toString(realtime))
                w.writeAttribute("runtime_raw", Double.toString(realtime))
            }
            w.writeAttribute("numcores", record.value.get("cpus").toString())

            //input files
            writeInputEdges(record.value, w)
            //output files
            writeOutputEdges(record.value, w)
            //close </job>
            w.writeEndElement()
        }
        //Dependencies
        w.writeComment(" part 3: list of control-flow dependencies (may be empty) ")
        writeDependencies(w)
        w.writeEndElement()
        w.writeEndDocument()
        w.flush()
        bw.flush()
        bw.close()

        //prettyprint the XML
        //1. parse the XML
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
        DocumentBuilder builder = factory.newDocumentBuilder()
        Document doc = builder.parse(path.toFile())
        //2. pretty print the XML
        Transformer tform = TransformerFactory.newInstance().newTransformer()
        tform.setOutputProperty(OutputKeys.INDENT, "yes")
        tform.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        tform.transform(new DOMSource(doc), new StreamResult(path.toFile()))
    }

    String generateNamespace() {
        String name = session.getWorkflowMetadata().projectName
        def split = name.split("/")
        return split[1]
    }

    void addFilesForRecord(TraceRecord record) {

        //the directory for this record
        Path path = Paths.get(record.get("workdir"))

        //the list of input files
        List<FileDependency> inputFiles = new ArrayList<>()

        //find the .commandFile (contains the names of the input-files)
        def commandFile = Files.walk(path)
                .map(file -> file.toFile())
                .filter(file -> file.name == ".command.run")
                .findFirst()
                .get()

        //parse input files from the .command file
        try {

            //create a reader and read all lines
            FileInputStream fileInputStream = new FileInputStream(commandFile)
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(fileInputStream))
            def lines = bufferedReader.readLines()

            // find the input file block
            List<String> inputLines = new ArrayList<String>()
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("# stage input files")) {
                    int count = 1
                    while (!lines.get(i + count).contains("}")) {
                        inputLines.add(lines.get(i + count))
                        count++
                    }
                }
            }

            //format the String lines into String files
            String[] inputFilesString = inputLines.stream()
                    .filter(s -> s.contains("/"))
                    .map(s -> s.split(" "))
                    .map(s -> s[s.size() - 2] + " " + s[s.size() - 1])
                    .toArray()

            //add all the input-files into the files List
            for (f in inputFilesString) {
                String fileName = f.toString().split(" ").last()
                String taskId = record.get("task_id")
                Path filePath = Paths.get(f.toString().split(" ").first())
                String tag = record.get("tag")
                long fileSize = Files.size(filePath)

                //create an input FileDependency
                FileDependency addInput = new FileDependency(fileName, taskId, filePath, tag, fileSize, false)

                //save input FileDependency to files List
                files.add(addInput)
                inputFiles.add(addInput)
            }
            fileInputStream.close()
        } catch (Exception e) {
            log.error("Error: " + e.getMessage())
        }
        //String list of input files
        String[] inputsString = files.stream()
                                    .filter(file -> !file.output)
                                    .filter(file -> file.taskId == record.get("task_id").toString())
                                    .map(file -> file.name)
                                    .toArray()

        //parse output file
        File[] outputFiles = Files.walk(path)
                .map(file -> file.toFile())
                .filter(file -> !file.name.startsWith("."))
                .filter(file -> file.name.contains("."))
                .filter(file -> !file.name.contains("log"))
                .filter(file -> !file.name.contains("err"))
                .filter(file -> !file.name.contains("info"))
                .filter(file -> !inputsString.contains(file.name))
                .toArray()

        //add output-files to files List
        try {
            for (file in outputFiles) {
                Path filePath = file.toPath()
                FileDependency addOutput = new FileDependency(file.name, record.get("task_id").toString(), filePath, record.get("tag").toString(), Files.size(filePath), true)
                files.add(addOutput)
        }
            }
        catch (IOException io){
            io.printStackTrace()
        }

    }

    void writeInputEdges(
            TraceRecord record, XMLStreamWriter w) {

        //filter all the inputs for this task
        FileDependency[] inputs = files.stream()
                    .filter(file -> !file.output)
                    .filter(file -> file.taskId == record.get("task_id").toString())
                    .toArray()

        //write down all the input files for this task
        for (i in inputs){
            w.writeStartElement("uses")
            w.writeAttribute("file", i.tag + "_" + i.name)
            w.writeAttribute("link", "input")
            w.writeAttribute("size", i.fileSize.toString())
            w.writeEndElement()
        }
    }

    void writeOutputEdges(TraceRecord record, XMLStreamWriter w) {

        HashSet<FileDependency> filtered = new HashSet<>()
        List<FileDependency> outputs = files.stream()
                .filter(file -> file.output)
                .filter(file -> file.taskId==record.get("task_id").toString())
                .filter(file -> filtered.add(file.name))
                .toArray()



        //write down all the output-files for this task
        for (o in outputs){
            w.writeStartElement("uses")
            w.writeAttribute("file", "task_id_" + o.taskId + "_" + o.name)
            w.writeAttribute("link", "output")
            w.writeAttribute("size", o.fileSize.toString())
            w.writeEndElement()
        }

    }



    void writeDependencies(XMLStreamWriter w){

        for (record in records){
            //get all inputs for this task
            FileDependency[] inputs = files.stream()
                            .filter(file -> !file.output)
                            .filter(file -> file.taskId == record.value.get("task_id").toString())
                            .toArray()
            //save the parents for this task
            List<String> parents = new ArrayList<>()

            for (input in inputs){
                ArrayList<String> parentsForInput = files.stream()
                        .filter(file -> file.output)
                        .filter(file -> file.name == input.name)
                        //filter out cycles
                        .filter(file -> file.taskId.toInteger()<record.value.taskId)
                        .map(file -> file.taskId)
                        .toArray()
                //add the parents for this file to the parents list for this task
                parents.addAll(parentsForInput)
                }
            //eliminate duplicates
            String[] parents_array = parents.stream().distinct().toArray()

            //print on the xml file
            if(parents_array.length>0){
                //<child ref="xyz">
                w.writeStartElement("child")
                w.writeAttribute("ref", record.value.get("task_id").toString())
                for(parent in parents_array){
                    //<parent>
                    w.writeStartElement("parent")
                    w.writeAttribute("ref", parent)
                    //</parent>
                    w.writeEndElement()
                }
                //</child>
                w.writeEndElement()
            }

        }

    }


    class FileDependency {
        String name
        String taskId
        Path directory
        String tag
        Long fileSize
        Boolean output

        FileDependency(String fileName, String taskId, Path workDirectory, String tag, Long fileSize, Boolean output) {
            this.name = fileName
            this.taskId = taskId
            this.directory = workDirectory
            this.tag = tag
            this.fileSize = fileSize
            this.output = output
        }

        String toString(){
            String inputOrOutput = output ? "Output" : "Input"
            return inputOrOutput + ": " + "name: " + this.name + " taskId: " + this.taskId + " directory: " \
            + this.directory.toString() + " tag: " + this.tag + " fileSize: " + this.fileSize
        }
    }

}