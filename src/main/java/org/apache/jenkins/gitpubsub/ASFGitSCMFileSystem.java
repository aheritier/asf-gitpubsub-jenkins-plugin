package org.apache.jenkins.gitpubsub;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.damnhandy.uri.template.UriTemplate;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.plugins.git.GitSCM;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitSCMTelescope;
import jenkins.plugins.git.GitTagSCMHead;
import jenkins.plugins.git.GitTagSCMRevision;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.eclipse.jgit.lib.Constants;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import static org.apache.jenkins.gitpubsub.ASFGitSCMNavigator.RFC_2822;

public class ASFGitSCMFileSystem extends SCMFileSystem {

    public static final Pattern URL_EXTRACT_H = Pattern.compile(".*[;?]h=([a-fA-F0-9]{40})([;?].*)?");
    static final int TEN_SECONDS_OF_MILLIS = 10000;
    private static final Logger LOGGER = Logger.getLogger(ASFGitSCMFileSystem.class.getName());
    static final List<String> GIT_WEB_HOSTS = new ArrayList<>(Arrays.asList(
            ASFGitSCMNavigator.GIT_WIP, ASFGitSCMNavigator.GIT_BOX
    ));
    private final String remote;
    private final String refOrHash;

    /**
     * Constructor.
     *
     * @param remote the remote.
     * @param head   the head.
     * @param rev    the revision.
     */
    public ASFGitSCMFileSystem(String remote, SCMHead head, SCMRevision rev) {
        super(rev instanceof AbstractGitSCMSource.SCMRevisionImpl ? rev : null);
        this.remote = remote;
        this.refOrHash = rev instanceof AbstractGitSCMSource.SCMRevisionImpl
                ? ((AbstractGitSCMSource.SCMRevisionImpl) rev).getHash()
                : (head instanceof GitTagSCMHead
                        ? Constants.R_TAGS + head.getName()
                        : Constants.R_HEADS + head.getName());
    }

    @Override
    public long lastModified() throws IOException, InterruptedException {
        if (refOrHash.startsWith(Constants.R_TAGS)) {
            String tagUrl = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                    .set("a", "tag")
                    .set("h", refOrHash)
                    .expand();
            Document doc;
            try {
                doc = Jsoup.parse(new URL(tagUrl), TEN_SECONDS_OF_MILLIS);
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 404) {
                    // must be a lightweight tag
                    doc = null;
                } else {
                    throw e;
                }
            }
            if (doc != null) {
                Elements elements = doc.select("table.object_header tr td span.datetime");
                try {
                    return new SimpleDateFormat(RFC_2822).parse(elements.get(0).text())
                            .getTime();
                } catch (ParseException e) {
                    throw new IOException(
                            "Unexpected date format, expected RFC 2822, got " + elements.get(1).text());
                } catch (IndexOutOfBoundsException e) {
                    throw new IOException(
                            "Unexpected response body, expecting two timestamps only got " + elements.size());
                }
            }
        }
        String commitUrl = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                .set("a", "commit")
                .set("h", refOrHash)
                .expand();
        Document doc = Jsoup.parse(new URL(commitUrl), TEN_SECONDS_OF_MILLIS);
        Elements elements = doc.select("table.object_header tr td span.datetime");
        try {
            return new SimpleDateFormat(RFC_2822).parse(elements.get(1).text()).getTime();
        } catch (ParseException e) {
            throw new IOException("Unexpected date format, expected RFC 2822, got " + elements.get(1).text());
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Unexpected response body, expecting two timestamps only got " + elements.size());
        }
    }

    @NonNull
    @Override
    public SCMFile getRoot() {
        return new ASFGitSCMFile(remote, refOrHash);
    }

    @Override
    public boolean changesSince(SCMRevision revision, @NonNull OutputStream changeLogStream)
            throws UnsupportedOperationException, IOException, InterruptedException {
        if (getRevision() == null ? revision == null : getRevision().equals(revision)) {
            // special case where somebody is asking one of two stupid questions:
            // 1. what has changed between the latest and the latest
            // 2. what has changed between the current revision and the current revision
            return false;
        }
        int count = 0;
        SimpleDateFormat rfc = new SimpleDateFormat(RFC_2822);
        FastDateFormat iso = FastDateFormat.getInstance("yyyy-MM-dd'T'HH:mm:ssZ");
        StringBuilder log = new StringBuilder(1024);
        StringBuilder para = new StringBuilder(1024);
        String endHash;
        if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl) {
            endHash = ((AbstractGitSCMSource.SCMRevisionImpl) revision).getHash().toLowerCase(Locale.ENGLISH);
        } else {
            endHash = null;
        }
        // this is the format expected by GitSCM, so we need to format each GHCommit with the same format
        // commit %H%ntree %T%nparent %P%nauthor %aN <%aE> %ai%ncommitter %cN <%cE> %ci%n%n%w(76,4,4)%s%n%n%b
        UriTemplate shortLogTemplate = buildTemplateWithRemote("{+server}{?p}{;a,h,pg}", remote)
                .set("a", "shortlog")
                .set("h", refOrHash);
        UriTemplate commitTemplate = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                .set("a", "commit");
        int pg = 0;
        while (count < GitSCM.MAX_CHANGELOG) {
            if (pg > 0) {
                shortLogTemplate.set("pg", pg);
            }
            pg++;
            Document doc = Jsoup.parse(new URL(shortLogTemplate.expand()), TEN_SECONDS_OF_MILLIS);
            for (Element element : doc.select("table.shortlog tr td a.subject")) {
                Matcher href = URL_EXTRACT_H.matcher(element.attr("href"));
                if (!href.matches()) {
                    continue;
                }
                if (href.group(1).toLowerCase(Locale.ENGLISH).equals(endHash)) {
                    return count > 0;
                }
                commitTemplate.set("h", href.group(1));
                Document commit = Jsoup.parse(new URL(commitTemplate.expand()), TEN_SECONDS_OF_MILLIS);
                log.setLength(0);
                Elements sha1s = commit.select("table.object_header tr td.sha1");
                log.append("commit ").append(sha1s.get(0).text().trim()).append('\n');
                log.append("tree ").append(sha1s.get(1).text().trim()).append('\n');
                log.append("parent");
                for (int i = 2; i < sha1s.size(); i++) {
                    log.append(' ').append(sha1s.get(i).text().trim());
                }
                log.append('\n');
                Elements persons = commit.select("table.object_header tr");
                try {
                    log.append("author ")
                            .append(persons.get(0).child(1).text().trim())
                            .append(' ')
                            .append(iso.format(rfc.parse(persons.get(1).child(1).text())))
                            .append('\n');
                    log.append("committer ")
                            .append(persons.get(2).child(1).text().trim())
                            .append(' ')
                            .append(iso.format(rfc.parse(persons.get(3).child(1).text())))
                            .append('\n');
                } catch (ParseException e) {
                    throw new IOException(e);
                }
                log.append('\n');
                Element messageDiv = commit.select("div.page_body").get(0);
                para.setLength(0);
                boolean inPara = false;
                for (Node node : messageDiv.childNodes()) {
                    if (node instanceof TextNode) {
                        String s = ((TextNode) node).text().trim();
                        if (!s.isEmpty()) {
                            if (para.length() > 0) {
                                para.append(' ');
                            }
                            para.append(s.replace('\u00a0', '\u0020'));
                            inPara = true;
                        }
                    } else if (node instanceof Element) {
                        if (((Element) node).tagName().equalsIgnoreCase("br")) {
                            if (inPara) {
                                inPara = false;
                            } else {
                                if (para.length() > 0) {
                                    log.append("    ")
                                            .append(WordUtils.wrap(para.toString(), 72, "\n    ", false));
                                    para.setLength(0);
                                }
                                log.append("\n\n");
                            }
                        }
                    }
                }
                if (para.length() > 0) {
                    log.append("    ")
                            .append(WordUtils.wrap(para.toString(), 72, "\n    ", false));
                    log.append('\n');
                }
                if (inPara) {
                    log.append('\n');
                }
                log.append('\n');
                changeLogStream.write(log.toString().getBytes(StandardCharsets.UTF_8));
                changeLogStream.flush();
                count++;
                if (count >= GitSCM.MAX_CHANGELOG) {
                    break;
                }
            }
        }
        return count > 0;
    }

    static UriTemplate buildTemplateWithRemote(String template, @NonNull String remote) throws IOException {
        UriTemplate commitTemplate;
        String server = null;
        String p = null;
        for (String prefix : GIT_WEB_HOSTS) {
            if (remote.startsWith(prefix + "/")) {
                server = prefix;
                p = remote.substring(prefix.length() + 1);
                break;
            }
        }
        if (server == null) {
            throw new IOException("Unknown remote: " + remote);
        }

        commitTemplate = UriTemplate.fromTemplate(template);
        commitTemplate.set("server", server).set("p", p);
        return commitTemplate;
    }


    @Extension
    public static class TelescopeImpl extends GitSCMTelescope {


        @Override
        public boolean supports(@NonNull String remote) {
            for (String prefix : GIT_WEB_HOSTS) {
                if (remote.startsWith(prefix + "/")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void validate(@NonNull String remote, StandardCredentials credentials)
                throws IOException, InterruptedException {
            // no-op because anonymous access
        }

        @Override
        protected SCMFileSystem build(@NonNull String remote, StandardCredentials credentials, @NonNull SCMHead head,
                                      SCMRevision rev) throws IOException, InterruptedException {
            return new ASFGitSCMFileSystem(remote, head, rev);
        }

        @Override
        public long getTimestamp(@NonNull String remote, StandardCredentials credentials, @NonNull String refOrHash)
                throws IOException, InterruptedException {
            if (refOrHash.startsWith(Constants.R_TAGS)) {
                String tagUrl = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                        .set("a", "tag")
                        .set("h", refOrHash)
                        .expand();
                Document doc;
                try {
                    doc = Jsoup.parse(new URL(tagUrl), TEN_SECONDS_OF_MILLIS);
                } catch (HttpStatusException e) {
                    if (e.getStatusCode() == 404) {
                        // must be a lightweight tag
                        doc = null;
                    } else {
                        throw e;
                    }
                }
                if (doc != null) {
                    Elements elements = doc.select("table.object_header tr td span.datetime");
                    try {
                        return new SimpleDateFormat(RFC_2822).parse(elements.get(0).text())
                                .getTime();
                    } catch (ParseException e) {
                        throw new IOException(
                                "Unexpected date format, expected RFC 2822, got " + elements.get(1).text());
                    } catch (IndexOutOfBoundsException e) {
                        throw new IOException(
                                "Unexpected response body, expecting two timestamps only got " + elements.size());
                    }
                }
            }
            String commitUrl = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                    .set("a", "commit")
                    .set("h", refOrHash)
                    .expand();
            Document doc = Jsoup.parse(new URL(commitUrl), TEN_SECONDS_OF_MILLIS);
            Elements elements = doc.select("table.object_header tr td span.datetime");
            try {
                return new SimpleDateFormat(RFC_2822).parse(elements.get(1).text()).getTime();
            } catch (ParseException e) {
                throw new IOException("Unexpected date format, expected RFC 2822, got " + elements.get(1).text());
            } catch (IndexOutOfBoundsException e) {
                throw new IOException("Unexpected response body, expecting two timestamps only got " + elements.size());
            }
        }

        @Override
        public SCMRevision getRevision(@NonNull String remote, StandardCredentials credentials,
                                       @NonNull String refOrHash)
                throws IOException, InterruptedException {
            if (refOrHash.startsWith(Constants.R_TAGS)) {
                // check if this is an annotated tag... if it is we need to get the tag object revision
                String tagUrl = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                        .set("a", "tag")
                        .set("h", refOrHash)
                        .expand();
                Document doc;
                try {
                    doc = Jsoup.parse(new URL(tagUrl), TEN_SECONDS_OF_MILLIS);
                } catch (HttpStatusException e) {
                    if (e.getStatusCode() == 404) {
                        // must be a lightweight tag
                        doc = null;
                    } else {
                        throw e;
                    }
                }
                if (doc != null) {
                    long time;
                    Elements elements = doc.select("table.object_header tr td span.datetime");
                    try {
                        time = new SimpleDateFormat(RFC_2822).parse(elements.get(0).text())
                                .getTime();
                    } catch (ParseException e) {
                        throw new IOException(
                                "Unexpected date format, expected RFC 2822, got " + elements.get(1).text());
                    } catch (IndexOutOfBoundsException e) {
                        throw new IOException(
                                "Unexpected response body, expecting two timestamps only got " + elements.size());
                    }
                    // now let's get the revision of the tag object...
                    String actionUrl = buildTemplateWithRemote("{+server}{?p}{;a}", remote)
                            .set("a", "tags")
                            .expand();
                    doc = Jsoup.parse(new URL(actionUrl), TEN_SECONDS_OF_MILLIS);
                    elements = doc.select("table.tags tr td a.name");
                    for (Element element : elements) {
                        if (refOrHash.equals(Constants.R_TAGS + element.text())) {
                            Elements links = element.parent().parent().select("td.selflink a");
                            if (links.isEmpty()) {
                                // assumption violated, bail
                                break;
                            }
                            String href = links.get(0).attr("href");
                            Matcher matcher = URL_EXTRACT_H.matcher(href);
                            if (matcher.matches()) {
                                return new GitTagSCMRevision(
                                        new GitTagSCMHead(refOrHash.substring(Constants.R_TAGS.length()), time),
                                        matcher.group(1));
                            }

                        }
                    }
                }
            }
            String commitUrl = buildTemplateWithRemote("{+server}{?p}{;a,h}", remote)
                    .set("a", "commit")
                    .set("h", refOrHash)
                    .expand();
            Document doc = Jsoup.parse(new URL(commitUrl), TEN_SECONDS_OF_MILLIS);
            Elements elements = doc.select("table.object_header tr td.sha1");
            String revision = elements.get(0).text();
            if (refOrHash.startsWith(Constants.R_TAGS)) {
                elements = doc.select("table.object_header tr td span.datetime");
                long time;
                try {
                    time =
                            new SimpleDateFormat(RFC_2822).parse(elements.get(1).text()).getTime();
                } catch (ParseException e) {
                    throw new IOException("Unexpected date format, expected RFC 2822, got " + elements.get(1).text());
                }
                return new GitTagSCMRevision(new GitTagSCMHead(refOrHash.substring(Constants.R_TAGS.length()), time),
                        revision);
            } else if (refOrHash.startsWith(Constants.R_HEADS)) {
                return new AbstractGitSCMSource.SCMRevisionImpl(
                        new SCMHead(refOrHash.substring(Constants.R_HEADS.length())), revision);
            } else {
                // TODO fix for hash without branch name
                return null;
            }
        }

        @Override
        public Iterable<SCMRevision> getRevisions(@NonNull String remote, StandardCredentials credentials,
                                                  @NonNull Set<ReferenceType> referenceTypes)
                throws IOException, InterruptedException {
            List<String> result = new ArrayList<>();
            List<String> tagRev = new ArrayList<>();
            TYPES:
            for (ReferenceType referenceType : referenceTypes) {
                String actionUrl;
                Document doc;
                Elements elements;
                String prefix;
                switch (referenceType) {
                    case HEAD:
                        actionUrl = buildTemplateWithRemote("{+server}{?p}{;a}", remote)
                                .set("a", "heads")
                                .expand();
                        doc = Jsoup.parse(new URL(actionUrl), TEN_SECONDS_OF_MILLIS);
                        elements = doc.select("table.heads tr td a.name");
                        prefix = Constants.R_HEADS;
                        break;
                    case TAG:
                        actionUrl = buildTemplateWithRemote("{+server}{?p}{;a}", remote)
                                .set("a", "tags")
                                .expand();
                        doc = Jsoup.parse(new URL(actionUrl), TEN_SECONDS_OF_MILLIS);
                        elements = doc.select("table.tags tr td a.name");
                        prefix = Constants.R_TAGS;
                        break;
                    default:
                        LOGGER.log(Level.WARNING, "Ignoring unexpected reference type {0}", referenceType);
                        continue TYPES;
                }
                for (Element element : elements) {
                    result.add(prefix + element.text());
                    if (referenceType != ReferenceType.TAG) {
                        tagRev.add(null);
                    } else {
                        // resolve the revision of the tag
                        Elements links = element.parent().parent().select("td.selflink a");
                        String href;
                        if (!links.isEmpty()) {
                            // annotated tag
                            href = links.get(0).attr("href");
                        } else {
                            // lightweight tag
                            href = element.attr("href");
                        }
                        if (href != null) {
                            Matcher matcher = URL_EXTRACT_H.matcher(href);
                            if (matcher.matches()) {
                                tagRev.add(matcher.group(1));
                            } else {
                                tagRev.add(null);
                            }
                        } else {
                            tagRev.add(null);
                        }
                    }
                }
            }
            return new AbstractList<SCMRevision>() {
                private final List<SCMRevision> cache = new ArrayList<>(result.size());

                {
                    for (int i = 0; i < result.size(); i++) {
                        cache.add(null);
                    }
                }

                @Override
                public SCMRevision get(int index) {
                    SCMRevision r = cache.get(index);
                    if (r == null) {
                        String hash = tagRev.get(index);
                        if (hash != null) {
                            try {
                                r = new GitTagSCMRevision(
                                        new GitTagSCMHead(
                                                result.get(index).substring(Constants.R_TAGS.length()),
                                                getTimestamp(remote, credentials, result.get(index))
                                        ),
                                        hash
                                );
                            } catch (IOException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            cache.set(index, r);
                        }
                    }
                    if (r == null) {
                        try {
                            r = getRevision(remote, credentials, result.get(index));
                        } catch (IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        cache.set(index, r);
                    }
                    return r;
                }

                @Override
                public int size() {
                    return result.size();
                }
            };
        }

        @Override
        public String getDefaultTarget(@NonNull String remote, StandardCredentials credentials)
                throws IOException, InterruptedException {
            String commitUrl = buildTemplateWithRemote("{+server}{?p}{;a}", remote)
                    .set("a", "heads")
                    .expand();
            Document doc = Jsoup.parse(new URL(commitUrl), TEN_SECONDS_OF_MILLIS);
            Elements elements = doc.select("table.heads tr td.current_head a.name");
            if (elements.size() > 0) {
                return Constants.R_HEADS + elements.get(0).text();
            }
            return null;
        }
    }
}
