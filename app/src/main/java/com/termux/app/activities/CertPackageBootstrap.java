package com.termux.app.activities;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Certificate-package bootstrap: when a container has no CA bundle, the
 *  phone downloads the distro's real ca-certificates package (plus any
 *  missing dependencies) from the package mirror and stages the files
 *  under /tmp/cert-pkgs; the agent install script then installs them
 *  locally before the package manager runs. This replaces the old PEM
 *  injection (0.10), which proved unreliable: a hand-written bundle kept
 *  tripping over file/directory permissions and ACLs, while the real
 *  package's postinst sets everything up with its own correct perms.
 *
 *  No Android classes except Log (and DistroUtils.caBundleRelPath for the
 *  bundle path), so the pure logic is unit-testable with a stub Log. */
public final class CertPackageBootstrap {

    private static final String TAG = "CertPackageBootstrap";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** Download/decompress buffer (64KB): package files and repo
     * indexes are megabytes, so the old 8KB buffer only wasted
     * syscalls. */
    private static final int IO_BUFFER_SIZE = 65536;

    /** Dependency closure bounds: at most this many packages, this deep. */
    private static final int MAX_PACKAGES = 10;
    private static final int MAX_DEP_DEPTH = 3;

    /** Stage dir inside the rootfs (relative). */
    static final String STAGE_DIR = "tmp/cert-pkgs";

    private CertPackageBootstrap() {
    }

    /** One package from a repo index. */
    static final class Pkg {
        final String name;
        /** Path of the package file, relative to the repo base URL. */
        final String location;
        final String checksum;
        /** "SHA-512", "SHA-256" or "SHA-1". */
        final String checksumAlgo;
        final List<String> depends;
        final boolean essential;

        Pkg(String name, String location, String checksum, String checksumAlgo,
                List<String> depends, boolean essential) {
            this.name = name;
            this.location = location;
            this.checksum = checksum;
            this.checksumAlgo = checksumAlgo;
            this.depends = depends != null ? depends : new ArrayList<String>();
            this.essential = essential;
        }
    }

    /** Ensure the container has CA certificates by staging the distro's
     *  ca-certificates package (and its dependencies) for a local install.
     *  Returns the staged files as rootfs-relative paths (e.g.
     *  "tmp/cert-pkgs/ca-certificates_20260816_all.deb"), or an empty list
     *  when the bundle already exists, the distro is unsupported, or every
     *  mirror failed. Never throws; a failure just means the install
     *  proceeds as before. Call on a background thread (it downloads). */
    public static List<String> ensureCertPackages(File rootfs, String distroId,
                                                  List<String> mirrorHosts) {
        List<String> empty = new ArrayList<>();
        if (rootfs == null || distroId == null || mirrorHosts == null || mirrorHosts.isEmpty()) {
            return empty;
        }
        try {
            String bundleRel = DistroUtils.caBundleRelPath(distroId);
            if (bundleRel != null && new File(rootfs, bundleRel).isFile()) {
                return empty; // Already has certificates: nothing to do.
            }
            String release = containerRelease(rootfs, distroId);
            if (release == null) return empty; // Unsupported distro or unreadable.
            for (String host : mirrorHosts) {
                if (host == null || host.isEmpty()) continue;
                List<String> staged = tryHost(rootfs, distroId, host, release);
                if (staged != null && !staged.isEmpty()) return staged;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "certificate package bootstrap failed for " + distroId, e);
        }
        return empty;
    }

    /** Bash for the install script: install the staged packages locally,
     *  then clean the stage dir. Empty when there is nothing to install.
     *  Runs before ensureCurlCommand, so the package manager can verify
     *  the mirrors afterwards. */
    public static String localInstallCommand(String distroId, List<String> relPaths) {
        if (relPaths == null || relPaths.isEmpty()) return "";
        StringBuilder files = new StringBuilder();
        for (String rel : relPaths) {
            if (rel == null || !rel.startsWith(STAGE_DIR + "/")) return "";
            files.append(" /").append(rel);
        }
        String install;
        switch (distroId) {
            case "ubuntu":
            case "debian":
                install = "dpkg -i" + files;
                break;
            case "arch":
                install = "pacman -U --noconfirm" + files;
                break;
            case "fedora":
            case "opensuse":
                install = "rpm -i" + files;
                break;
            case "alpine":
                install = "apk add --allow-untrusted" + files;
                break;
            default:
                return "";
        }
        return install + " || { echo \"证书安装包安装失败，退出\"; exit 1; }\nrm -rf /tmp/cert-pkgs";
    }

    // ------------------------------------------------------------------
    // Per-host attempt
    // ------------------------------------------------------------------

    /** Resolve + download + stage the package set from one mirror host.
     *  Null when this host cannot supply a complete verified set. */
    private static List<String> tryHost(File rootfs, String distroId, String host, String release) {
        try {
            String base = repoBaseUrl(distroId, host, release);
            if (base == null) return null;
            Map<String, Pkg> index = fetchIndex(distroId, base, release);
            if (index == null) return null;
            Pkg ca = index.get("ca-certificates");
            if (ca == null) return null;
            LinkedHashMap<String, Pkg> closure = resolveClosure(index, ca);
            if (closure.isEmpty() || closure.size() > MAX_PACKAGES) return null;
            File dir = new File(rootfs, STAGE_DIR);
            if (!(dir.isDirectory() || dir.mkdirs())) return null;
            cleanStageDir(dir);
            List<String> staged = new ArrayList<>();
            for (Pkg pkg : closure.values()) {
                String fileName = fileNameOf(pkg.location);
                if (fileName == null) return null;
                File out = new File(dir, fileName);
                if (!downloadToFile(base + "/" + pkg.location, out, pkg.checksum, pkg.checksumAlgo)) {
                    return null;
                }
                staged.add(STAGE_DIR + "/" + fileName);
            }
            return staged;
        } catch (RuntimeException e) {
            Log.w(TAG, "certificate package bootstrap failed for host " + host, e);
            return null;
        }
    }

    /** Repo base URL for one distro + mirror host. The package locations in
     *  the index are relative to this base. */
    private static String repoBaseUrl(String distroId, String host, String release) {
        switch (distroId) {
            case "ubuntu":
                return "https://" + host + "/ubuntu";
            case "debian":
                return "https://" + host + "/debian";
            case "alpine":
                return "https://" + host + "/alpine";
            case "arch":
                return "https://" + host + "/archlinuxarm/aarch64";
            case "fedora":
                return "https://" + host + "/pub/fedora/linux/releases/" + release
                        + "/Everything/aarch64/os";
            case "opensuse":
                if ("tumbleweed".equals(release)) {
                    return "https://" + host + "/tumbleweed/repo/oss";
                }
                if (release.startsWith("leap:")) {
                    return "https://" + host + "/distribution/leap/"
                            + release.substring(5) + "/repo/oss";
                }
                return null;
            default:
                return null;
        }
    }

    /** Fetch + parse the repo index for one distro. Null on any failure. */
    private static Map<String, Pkg> fetchIndex(String distroId, String base, String release) {
        try {
            switch (distroId) {
                case "ubuntu":
                case "debian": {
                    byte[] gz = downloadBytes(base + "/dists/" + release
                            + "/main/binary-arm64/Packages.gz");
                    if (gz == null) return null;
                    byte[] text = gunzip(gz);
                    if (text == null) return null;
                    return parseDebianPackages(new String(text, StandardCharsets.UTF_8));
                }
                case "alpine": {
                    byte[] gz = downloadBytes(base + "/v" + release + "/main/aarch64/APKINDEX.tar.gz");
                    if (gz == null) return null;
                    byte[] index = tarExtract(gunzip(gz), "APKINDEX");
                    if (index == null) return null;
                    return parseApkIndex(new String(index, StandardCharsets.UTF_8), release);
                }
                case "arch": {
                    byte[] gz = downloadBytes(base + "/core/core.db");
                    if (gz == null) return null;
                    return parseArchDb(gunzip(gz));
                }
                case "fedora":
                case "opensuse": {
                    byte[] repomd = downloadBytes(base + "/repodata/repomd.xml");
                    if (repomd == null) return null;
                    String primary = repomdPrimaryLocation(
                            new String(repomd, StandardCharsets.UTF_8));
                    if (primary == null) return null;
                    byte[] gz = downloadBytes(base + "/" + primary);
                    if (gz == null) return null;
                    return parsePrimaryXml(gunzip(gz));
                }
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "fetching index failed for " + distroId, e);
            return null;
        }
    }

    /** Dependency closure of one package: the package itself plus its
     *  non-essential dependencies, recursively. A dependency that is not
     *  in the index is treated as provided by the base system and skipped
     *  (virtual provides like so:/pc: in APKINDEX, or file/capability
     *  requirements in RPM). Essential (Debian) packages are skipped:
     *  a rootfs cannot exist without them. */
    static LinkedHashMap<String, Pkg> resolveClosure(Map<String, Pkg> index, Pkg root) {
        LinkedHashMap<String, Pkg> out = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<Pkg> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        while (!queue.isEmpty() && out.size() < MAX_PACKAGES) {
            Pkg pkg = queue.poll();
            int depth = depths.poll();
            if (pkg == null || !seen.add(pkg.name)) continue;
            if (!pkg.essential) out.put(pkg.name, pkg);
            if (depth >= MAX_DEP_DEPTH) continue;
            for (String dep : pkg.depends) {
                Pkg depPkg = index.get(dep);
                if (depPkg == null) {
                    Log.w(TAG, "dependency " + dep + " of " + pkg.name
                            + " not in index; assuming provided by base");
                    continue;
                }
                if (!seen.contains(dep)) {
                    queue.add(depPkg);
                    depths.add(depth + 1);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Release detection (host-side reads from the rootfs)
    // ------------------------------------------------------------------

    /** Release key the index URL needs, or null when unsupported/unreadable.
     *  ubuntu/debian: suite codename; alpine: major.minor; fedora: version;
     *  opensuse: "tumbleweed" or "leap:<version>"; arch: "" (rolling). */
    static String containerRelease(File rootfs, String distroId) {
        try {
            switch (distroId) {
                case "ubuntu":
                case "debian": {
                    Map<String, String> os = parseOsRelease(readText(new File(rootfs, "etc/os-release")));
                    String codename = os.get("VERSION_CODENAME");
                    return codename != null && !codename.isEmpty() ? codename : null;
                }
                case "alpine": {
                    String ver = readText(new File(rootfs, "etc/alpine-release"));
                    if (ver == null) return null;
                    ver = ver.trim();
                    String[] parts = ver.split("\\.");
                    if (parts.length < 2) return null;
                    return parts[0] + "." + parts[1];
                }
                case "fedora": {
                    Map<String, String> os = parseOsRelease(readText(new File(rootfs, "etc/os-release")));
                    String id = os.get("VERSION_ID");
                    return id != null && !id.isEmpty() ? id : null;
                }
                case "opensuse": {
                    Map<String, String> os = parseOsRelease(readText(new File(rootfs, "etc/os-release")));
                    String id = os.get("ID");
                    if ("opensuse-tumbleweed".equals(id)) return "tumbleweed";
                    if ("opensuse-leap".equals(id)) {
                        String ver = os.get("VERSION_ID");
                        return ver != null && !ver.isEmpty() ? "leap:" + ver : null;
                    }
                    return null;
                }
                case "arch":
                    return "";
                default:
                    return null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readText(File file) throws IOException {
        if (file == null || !file.isFile()) return null;
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Parse an os-release file (KEY=VALUE, quotes stripped). */
    static Map<String, String> parseOsRelease(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Index parsers
    // ------------------------------------------------------------------

    /** Debian/Ubuntu Packages file: blank-line separated stanzas. */
    static Map<String, Pkg> parseDebianPackages(String text) {
        Map<String, Pkg> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String stanza : text.split("\n\n")) {
            Map<String, String> fields = new LinkedHashMap<>();
            String lastKey = null;
            for (String line : stanza.split("\n")) {
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (lastKey != null) {
                        fields.put(lastKey, fields.get(lastKey) + " " + line.trim());
                    }
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                lastKey = line.substring(0, colon).trim();
                fields.put(lastKey, line.substring(colon + 1).trim());
            }
            String name = fields.get("Package");
            String filename = fields.get("Filename");
            String sha512 = fields.get("SHA512");
            if (name == null || filename == null || sha512 == null) continue;
            List<String> depends = new ArrayList<>();
            String depField = fields.get("Depends");
            if (depField != null) {
                for (String part : depField.split(",")) {
                    String dep = debDepName(part);
                    if (dep != null) depends.add(dep);
                }
            }
            out.put(name, new Pkg(name, filename, sha512, "SHA-512", depends,
                    "yes".equals(fields.get("Essential"))));
        }
        return out;
    }

    /** One Debian dependency alternative: first choice, version and arch
     *  qualifiers stripped ("openssl (>= 1.1.1)" -> "openssl"). */
    static String debDepName(String part) {
        if (part == null) return null;
        String first = part.split("\\|")[0].trim();
        int paren = first.indexOf('(');
        if (paren >= 0) first = first.substring(0, paren).trim();
        int bracket = first.indexOf('[');
        if (bracket >= 0) first = first.substring(0, bracket).trim();
        return first.isEmpty() ? null : first;
    }

    /** Alpine APKINDEX: blank-line separated "K:value" stanzas. The .apk
     *  filename is built from the name + version fields. */
    static Map<String, Pkg> parseApkIndex(String text, String release) {
        Map<String, Pkg> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String stanza : text.split("\n\n")) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : stanza.split("\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            String name = fields.get("P");
            String version = fields.get("V");
            String checksum = fields.get("C");
            if (name == null || version == null || checksum == null) continue;
            List<String> depends = new ArrayList<>();
            String depField = fields.get("D");
            if (depField != null) {
                for (String token : depField.split("\\s+")) {
                    String dep = apkDepName(token);
                    if (dep != null) depends.add(dep);
                }
            }
            String location = "v" + release + "/main/aarch64/" + name + "-" + version + ".apk";
            out.put(name, new Pkg(name, location, checksum, "SHA-1", depends, false));
        }
        return out;
    }

    /** One Alpine dependency token: plain package names only; "so:"/"pc:"
     *  provides and negations are skipped, version suffixes stripped. */
    static String apkDepName(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.startsWith("so:") || token.startsWith("pc:") || token.startsWith("!")) return null;
        int cut = token.length();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '=' || c == '<' || c == '>' || c == '~') {
                cut = i;
                break;
            }
        }
        String name = token.substring(0, cut);
        return name.isEmpty() ? null : name;
    }

    /** Arch core.db: a tarball of "<name>-<version>/desc" files. */
    static Map<String, Pkg> parseArchDb(byte[] tar) {
        Map<String, Pkg> out = new LinkedHashMap<>();
        if (tar == null) return out;
        for (Map.Entry<String, byte[]> entry : tarEntries(tar).entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith("/desc")) continue;
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            Map<String, List<String>> sections = new LinkedHashMap<>();
            String section = null;
            for (String line : text.split("\n")) {
                if (line.startsWith("%") && line.endsWith("%")) {
                    section = line;
                    sections.put(section, new ArrayList<String>());
                } else if (section != null && !line.isEmpty()) {
                    sections.get(section).add(line);
                }
            }
            String name = firstOf(sections, "%NAME%");
            String filename = firstOf(sections, "%FILENAME%");
            String sha256 = firstOf(sections, "%SHA256SUM%");
            if (name == null || filename == null || sha256 == null) continue;
            List<String> depends = new ArrayList<>();
            List<String> depLines = sections.get("%DEPENDS%");
            if (depLines != null) {
                for (String depLine : depLines) {
                    String dep = archDepName(depLine);
                    if (dep != null) depends.add(dep);
                }
            }
            out.put(name, new Pkg(name, "core/" + filename, sha256, "SHA-256", depends, false));
        }
        return out;
    }

    static String archDepName(String line) {
        if (line == null) return null;
        String dep = line.trim();
        int cut = dep.length();
        for (int i = 0; i < dep.length(); i++) {
            char c = dep.charAt(i);
            if (c == '=' || c == '<' || c == '>') {
                cut = i;
                break;
            }
        }
        dep = dep.substring(0, cut).trim();
        return dep.isEmpty() ? null : dep;
    }

    private static String firstOf(Map<String, List<String>> sections, String key) {
        List<String> values = sections.get(key);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    /** repomd.xml: the location of the primary.xml.gz (DOM; the file is small). */
    static String repomdPrimaryLocation(String xml) {
        if (xml == null) return null;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList data = doc.getElementsByTagName("data");
            for (int i = 0; i < data.getLength(); i++) {
                Element el = (Element) data.item(i);
                if (!"primary".equals(el.getAttribute("type"))) continue;
                NodeList locations = el.getElementsByTagName("location");
                if (locations.getLength() > 0) {
                    return ((Element) locations.item(0)).getAttribute("href");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parsing repomd.xml failed", e);
        }
        return null;
    }

    /** Fedora/openSUSE primary.xml: SAX parse into a name -> Pkg map. */
    static Map<String, Pkg> parsePrimaryXml(byte[] xmlBytes) {
        final Map<String, Pkg> out = new LinkedHashMap<>();
        if (xmlBytes == null) return out;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            parser.parse(new ByteArrayInputStream(xmlBytes), new DefaultHandler() {
                private PkgBuilder current;
                private boolean inRequires;
                private String currentTag;
                private StringBuilder text;

                @Override
                public void startElement(String uri, String localName, String qName,
                                         Attributes attrs) {
                    if ("package".equals(qName)) {
                        current = new PkgBuilder();
                    } else if (current != null && "rpm:requires".equals(qName)) {
                        inRequires = true;
                    } else if (current != null && "rpm:entry".equals(qName) && inRequires) {
                        String dep = attrs.getValue("name");
                        if (dep != null && !dep.contains("/") && !dep.contains("(")) {
                            current.depends.add(dep);
                        }
                    } else if (current != null && ("name".equals(qName)
                            || "location".equals(qName) || "checksum".equals(qName))) {
                        currentTag = qName;
                        text = new StringBuilder();
                        if ("location".equals(qName)) {
                            current.location = attrs.getValue("href");
                        }
                        if ("checksum".equals(qName)) {
                            current.checksumAlgo = "sha256".equals(attrs.getValue("type"))
                                    ? "SHA-256" : null;
                        }
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (text != null) text.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    if ("package".equals(qName)) {
                        if (current != null && current.name != null && current.location != null
                                && current.checksum != null && current.checksumAlgo != null) {
                            out.put(current.name, new Pkg(current.name, current.location,
                                    current.checksum, current.checksumAlgo, current.depends, false));
                        }
                        current = null;
                    } else if ("rpm:requires".equals(qName)) {
                        inRequires = false;
                    } else if (current != null && qName.equals(currentTag) && text != null) {
                        String value = text.toString().trim();
                        if ("name".equals(qName)) current.name = value;
                        if ("checksum".equals(qName) && current.checksumAlgo != null) {
                            current.checksum = value;
                        }
                        currentTag = null;
                        text = null;
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "parsing primary.xml failed", e);
        }
        return out;
    }

    private static final class PkgBuilder {
        String name;
        String location;
        String checksum;
        String checksumAlgo;
        final List<String> depends = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // Download + verify
    // ------------------------------------------------------------------

    /** Download a URL to bytes; null on any failure. */
    private static byte[] downloadBytes(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buf = new byte[IO_BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) >= 0) bytes.write(buf, 0, n);
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "downloading " + urlString + " failed", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Download a URL to a file, verifying the checksum while streaming.
     *  The partial file is deleted on failure. */
    private static boolean downloadToFile(String urlString, File out, String checksum, String algo) {
        HttpURLConnection connection = null;
        try {
            MessageDigest digest = MessageDigest.getInstance(algo);
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream fileOut = new FileOutputStream(out)) {
                byte[] buf = new byte[IO_BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                    fileOut.write(buf, 0, n);
                }
            }
            if (checksum == null) return false;
            if (!checksumMatches(digest.digest(), checksum)) {
                Log.w(TAG, "checksum mismatch for " + urlString);
                out.delete();
                return false;
            }
            return out.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "downloading " + urlString + " failed", e);
            if (out != null) out.delete();
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Case-insensitive checksum comparison (index values are lowercase
     *  hex; tolerate surrounding whitespace). Null expected never matches. */
    static boolean checksumMatches(byte[] digest, String expected) {
        if (digest == null || expected == null) return false;
        return toHex(digest).equalsIgnoreCase(expected.trim());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static byte[] gunzip(byte[] gz) {
        if (gz == null) return null;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[IO_BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Minimal tar reader: name -> file bytes (directories are skipped;
     *  GNU longname ('L') and pax extended-header ('x', path=) entries
     *  supply the real name of the following file). */
    static Map<String, byte[]> tarEntries(byte[] tar) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (tar == null) return out;
        int pos = 0;
        String longName = null;
        while (pos + 512 <= tar.length) {
            String name = readTarString(tar, pos, 100);
            if (name.isEmpty()) break;
            long size = readTarOctal(tar, pos + 124, 12);
            char type = (char) tar[pos + 156];
            int dataStart = pos + 512;
            if (dataStart + size > tar.length) break;
            if (type == 'L') {
                longName = readTarString(tar, dataStart, (int) size);
            } else if (type == 'x') {
                // pax extended header: "len keyword=value" records, one
                // per line; "path=" carries the real (long) name.
                String pax = new String(tar, dataStart, (int) size, StandardCharsets.UTF_8);
                for (String record : pax.split("\n")) {
                    int sp = record.indexOf(' ');
                    if (sp > 0) {
                        String kv = record.substring(sp + 1);
                        if (kv.startsWith("path=")) longName = kv.substring(5);
                    }
                }
            } else if (type == '0' || type == '\0') {
                String fullName = longName != null ? longName : name;
                byte[] data = new byte[(int) size];
                System.arraycopy(tar, dataStart, data, 0, (int) size);
                out.put(fullName, data);
                longName = null;
            }
            pos = dataStart + (int) ((size + 511) / 512 * 512);
        }
        return out;
    }

    /** Extract one file from a tarball by exact name; null when absent. */
    private static byte[] tarExtract(byte[] tar, String wanted) {
        return tarEntries(tar).get(wanted);
    }

    private static String readTarString(byte[] tar, int offset, int maxLen) {
        int end = offset;
        int limit = Math.min(tar.length, offset + maxLen);
        while (end < limit && tar[end] != 0) end++;
        return new String(tar, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long readTarOctal(byte[] tar, int offset, int len) {
        long value = 0;
        for (int i = offset; i < offset + len && i < tar.length; i++) {
            byte b = tar[i];
            if (b == 0 || b == ' ') break;
            if (b < '0' || b > '7') return 0;
            value = value * 8 + (b - '0');
        }
        return value;
    }

    /** Basename of a package location; null when the name is unsafe
     *  (path separators or characters outside the package-name set). */
    static String fileNameOf(String location) {
        if (location == null) return null;
        int slash = location.lastIndexOf('/');
        String name = slash >= 0 ? location.substring(slash + 1) : location;
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.startsWith(".") || !name.matches("[A-Za-z0-9._+~:-]+")) return null;
        return name;
    }

    private static void cleanStageDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) f.delete();
        }
    }
}
