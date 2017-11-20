package org.apache.jenkins.gitpubsub;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.scm.api.SCMFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import static org.apache.jenkins.gitpubsub.ASFGitSCMFileSystem.TEN_SECONDS_OF_MILLIS;

public class ASFGitSCMFile extends SCMFile {

    private final String remote;
    private final String refOrHash;

    ASFGitSCMFile(String remote, String refOrHash) {
        this.remote = remote;
        this.refOrHash = refOrHash;
    }

    ASFGitSCMFile(@NonNull ASFGitSCMFile parent, String name) {
        super(parent, name);
        this.remote = parent.remote;
        this.refOrHash = parent.refOrHash;
    }

    @NonNull
    @Override
    protected SCMFile newChild(@NonNull String name, boolean assumeIsDirectory) {
        return new ASFGitSCMFile(this, name);
    }

    @NonNull
    @Override
    public Iterable<SCMFile> children() throws IOException, InterruptedException {
        String treeUrl = ASFGitSCMFileSystem.buildTemplateWithRemote("{+server}{?p}{;a,hb,f}", remote)
                .set("a", "tree")
                .set("hb", refOrHash)
                .set("f", getPath())
                .expand();
        Document doc = Jsoup.parse(new URL(treeUrl), TEN_SECONDS_OF_MILLIS);
        Elements elements = doc.select("table.tree tr td.list a.list");
        List<SCMFile> result = new ArrayList<>();
        for (Element element : elements) {
            if (element.text().equals(getName())) {
                Element mode = element.parent().previousElementSibling().previousElementSibling();
                if (mode.text().startsWith("d")) {
                    result.add(newChild(element.text(), true));
                } else {
                    result.add(newChild(element.text(), false));
                }
            }
        }
        return result;
    }

    @Override
    public long lastModified() throws IOException, InterruptedException {
        return 0;
    }

    @NonNull
    @Override
    protected Type type() throws IOException, InterruptedException {
        String path = getPath();
        int lastSlash = path.lastIndexOf('/');
        String treeUrl = ASFGitSCMFileSystem.buildTemplateWithRemote("{+server}{?p}{;a,hb,f}", remote)
                .set("a", "tree")
                .set("hb", refOrHash)
                .set("f", lastSlash == -1 ? null : path.substring(0, lastSlash))
                .expand();
        Document doc = Jsoup.parse(new URL(treeUrl), TEN_SECONDS_OF_MILLIS);
        Elements elements = doc.select("table.tree tr td.list a.list");
        for (Element element : elements) {
            if (element.text().equals(getName())) {
                Element mode = element.parent().previousElementSibling().previousElementSibling();
                if (mode.text().startsWith("d")) {
                    return Type.DIRECTORY;
                } else if (mode.text().startsWith("-")) {
                    return Type.REGULAR_FILE;
                }
            }
        }
        return Type.NONEXISTENT;
    }

    @NonNull
    @Override
    public InputStream content() throws IOException, InterruptedException {
        String blobUrl = ASFGitSCMFileSystem.buildTemplateWithRemote("{+server}{?p}{;a,f,hb}", remote)
                .set("a", "blob_plain")
                .set("hb", refOrHash)
                .set("f", getPath())
                .expand();
        return new URL(blobUrl).openStream();
    }
}