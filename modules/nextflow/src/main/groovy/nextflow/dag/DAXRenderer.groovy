package nextflow.dag

import nextflow.Session
import nextflow.processor.TaskId
import nextflow.trace.TraceRecord
import groovy.text.GStringTemplateEngine
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.logging.Logger
import java.nio.file.Path
import groovy.transform.PackageScope

@Slf4j

/**
 * Render the DAG in .dax Format representing the Workflow.
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

    private String name
    private Session session

    private List<FileDependency> files


    /**
     * Finals for creating the .dax file
     */
    private static final String XMLNS = "http://pegasus.isi.edu/schema/DAX"
    private static final String XMLNS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private static final String XSI_LOCATION = XMLNS + " http://pegasus.isi.edu/schema/dax-2.1.xsd"
    private static final String VERSION = "2.1"


    /**
     * Create a DAXRenderer
     * @param dag
     * @param records
     */
    DAXRenderer(dag, records, path, namespace) {
        this.dag = dag
        this.records = records
        this.path = path
        this.namespace = namespace
    }

    DAXRenderer(String name, Map<TaskId, TraceRecord> records, Session session) {
        this.name = name
        this.records = records
        this.session = session
        this.namespace = generateNamespace()
        this.files = new ArrayList<FileDependency>()
    }

    void renderDocument(DAG dag, Path file) {
        this.dag = dag
        this.path = file
        log.info("---------------------------------------------------------------------")
        log.info("renderDocument was started  ")
        log.info("---------------------------------------------------------------------")
        log.info("records.size: " + records.size())
        log.info("")
//        for (r in records) {
//            log.info(r.toString())
//            log.info("")
//        }
//        log.info("---------------------------------------------------------------------")
//        log.info("DAG.vertices.size: " + dag.vertices.size())
//        log.info("")
//        for (n in dag.vertices) {
//            log.info(n.getName() + " " + n.toString())
//            log.info("")
//        }
//        log.info("---------------------------------------------------------------------")
//        log.info("DAG.edges.size: " + dag.edges.size())
//        log.info("")
//        for (edge in dag.edges) {
//            log.info("DAG.edge--> label:" + edge.label + " from: " + edge.from.name + " " + edge.from + " to: " + edge.to.name + " " + edge.to)
//            log.info("")
//        }
        renderDAX()
    }

    void renderDAX() {

        //XML File erstellen
        final Charset charset = Charset.defaultCharset()
        Writer bw = Files.newBufferedWriter(this.path, charset)
        final XMLOutputFactory xof = XMLOutputFactory.newFactory()
        final XMLStreamWriter w = xof.createXMLStreamWriter(bw)
        w.writeStartDocument(charset.displayName(), "1.0")
        w.writeComment(" generated: " + new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime()))
        w.writeComment(" generated by: " + System.getProperty("user.name") + " ")
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

        //List of files
        def edges = dag.edges
        w.writeComment(" part 1: list of all referenced files (may be empty) ")
        def refFiles = edges.stream()
                .filter(edge -> edge.from
                        != null)
                .filter(edge -> edge.from.type == DAG.Type.ORIGIN)
                .map(edge ->
                        edge.from.label.toString())
                        .toArray()
        for (file in refFiles) {
            if (file.toString() == "null") {
                break
            }
            w.writeStartElement("file")
            w.writeAttribute("name", file.toString())
            w.writeEndElement()
        }

        //List of Jobs
        w.writeComment(" part 2: definition of all jobs (at least one) ")
        for (record in records) {
            //add files to file list
            addFilesForRecord(record.value)
            files.forEach(file -> log.info(file.name +" "+file.fromId+ " size: "+ file.fileSize.toString()))
            //<job>-element + attributes
            w.writeStartElement("job")
            String id = record.value.get("task_id").toString()
            w.writeAttribute("id", id)
            w.writeAttribute("namespace", namespace)
            String name = record.value.get("name")
            w.writeAttribute("name", name)
            double realtime = record.value.get("realtime") / 1000
            w.writeAttribute("runtime", Double.toString(realtime))
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
    }

    String generateNamespace() {
        String name = session.getWorkflowMetadata().projectName
        def split = name.split("/")
        return split[1]
    }

    void writeInputEdges(TraceRecord record, XMLStreamWriter w) {
        w.writeStartElement("uses")
        if (records.values().stream().filter(r -> r.get("process").toString() ==  \
                 record.get("process").toString()).count() == 1) {
            def edges = dag.edges.stream()
                    .filter(edge -> edge.to.label == record.get("process"))
            w.writeCharacters("inputfiles")
        } else {
            w.writeCharacters("inputfiles")
        }
        w.writeEndElement()
    }

    void writeOutputEdges(TraceRecord record, XMLStreamWriter w) {
        w.writeStartElement("uses")
        w.writeCharacters("outputfiles")
        w.writeEndElement()
    }

    void writeDependencies(XMLStreamWriter w) {

    }

    void addFilesForRecord(TraceRecord record) {

        //log.warn("erster TraceRecord erreicht")
        Path path = Paths.get(record.get("workdir"))
        log.warn("path string: " + path.toString())
        //log.info("path size: " + path.size())
        //path.eachFile { it -> log.info(it.name) }
        log.info("")
        def commandFile = Files.walk(path)
                .map(file -> file.toFile())
                .filter(file -> file.name == ".command.run")
                .findFirst()
                .get()
        //parse input and output files
        try {
            FileInputStream fstream = new FileInputStream(commandFile)
            BufferedReader br = new BufferedReader(new InputStreamReader(fstream))
            def lines = br.readLines()
            List<String> inputsString = new ArrayList<String>()
            def outputsString
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("# stage input files")) {
                    //log.info("found input files!")
                    int count = 1
                    while (!lines.get(i + count).contains("}")) {
                        inputsString.add(lines.get(i + count))
                        log.info(lines.get(i + count))
                        count++
                    }
                }
            }
            //def inputSize = inputsString.stream().filter(s -> s.startsWith("ln -s")).count()
            log.info("")
            log.info("--------------------------------------------------------------------")
            def inputFiles = inputsString.stream()
                    .filter(s -> s.contains("/"))
                    .map(s -> s.split(" "))
                    .map(s -> s[s.size() - 2] + " " + s[s.size() - 1])
                    .toArray()
            for (f in inputFiles) {
                String fileName = f.toString().split(" ").last()
                Path pathFrom = Paths.get(f.toString().split(" ").first())
                String fromId = record.get("task_id")
                //file size in kilo bytes
                long fileSize = Files.size(pathFrom)/1024
                files.add(new FileDependency(fileName, pathFrom, fromId, fileSize))
            }
            fstream.close()
        } catch (Exception e) {
            log.error("Error: " + e.getMessage())
        }
    }



    class FileDependency {
        String name
        Path directoryFrom
        List<Path> directoriesTo = new ArrayList<>()
        String fromId
        List<String> toIds = new ArrayList<>()
        Double fileSize

        FileDependency(String name, Path directoryFrom, String fromId, Long fileSize) {
            this.name = name
            this.directoryFrom = directoryFrom
            this.fromId = fromId
            this.fileSize = fileSize
        }

        void addDirecoriesTo(Path to, String toId) {
            directoriesTo.add(to)
            toIds.add(toId)
        }

    }

}