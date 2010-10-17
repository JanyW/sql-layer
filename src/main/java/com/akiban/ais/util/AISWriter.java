package com.akiban.ais.util;

import com.akiban.ais.io.CSVTarget;
import com.akiban.ais.io.MySQLTarget;
import com.akiban.ais.io.Writer;
import com.akiban.ais.model.AkibaInformationSchema;
import com.akiban.ais.model.Target;

import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;

public class AISWriter
{
    public static void main(String[] args) throws Exception
    {
        new AISWriter(args).run();
    }

    private AISWriter(String[] args)
    {
        int a = 0;
        String format = args[a++];
        try {
            if (format.equals("--mysql")) {
                String hostAndPort = args[a++];
                int colon = hostAndPort.indexOf(':');
                if (colon < 0) {
                    mysqlHost = hostAndPort;
                    mysqlPort = DEFAULT_MYSQL_PORT;
                } else {
                    mysqlHost = hostAndPort.substring(colon);
                    mysqlPort = Integer.parseInt(hostAndPort.substring(colon + 1));
                }
                while (a < args.length) {
                    String flag = args[a++];
                    String value = args[a++];
                    if (flag.equals("--user")) {
                        mysqlUser = value;
                    } else if (flag.equals("--password")) {
                        mysqlPassword = value;
                    } else {
                        usage();
                    }
                }
            } else if (format.equals("--csv")) {
                csvFilename = args[a++];
            } else if (format.equals("--java")) {
                javaFilename = args[a++];
            } else {
                usage();
            }
        } catch (Exception e) {
            usage();
        }
    }

    private void run() throws Exception
    {
        AkibaInformationSchema ais = (AkibaInformationSchema) new ObjectInputStream(System.in).readObject();
        if (mysqlHost != null) {
            Target target = new MySQLTarget(mysqlHost, mysqlUser, mysqlPassword, mysqlPort);
            new Writer(target).save(ais);
        } else if (csvFilename != null) {
            Target target = new CSVTarget(new PrintWriter(csvFilename));
            new Writer(target).save(ais);
        } else if (javaFilename != null) {
            ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(javaFilename));
            output.writeObject(ais);
        } else {
            usage();
        }
    }

    private void usage()
    {
        for (String line : USAGE) {
            System.out.println(line);
        }
        System.exit(1);
    }

    private static final String[] USAGE = {
        "aiswrite --mysql HOST[:PORT] --user USER [--password PASSWORD]",
        "aiswrite --csv FILENAME",
        "aiswrite --java FILENAME",
        "",
        "An AIS is piped in, typically by running aisread.",
        "",
        "--mysql writes an AIS to the database on the indicated HOST. If PORT is not specified, the MySQL default of ",
        "3306 is used. The database connection is made as USER, identified by PASSWORD if supplied.",
        "",
        "--csv writes a CSV-formatted AIS to FILENAME.",
        "",
        "--java writes a java-serialized AIS to FILENAME."
    };

    private static final int DEFAULT_MYSQL_PORT = 3306;

    private String mysqlHost;
    private int mysqlPort;
    private String mysqlUser;
    private String mysqlPassword;
    private String csvFilename;
    private String javaFilename;
}