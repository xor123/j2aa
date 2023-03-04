package club.kanban.j2aa;

import club.kanban.j2aaconverter.J2aaConverter;
import club.kanban.jirarestclient.Board;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import lombok.Getter;
import lombok.Setter;
import net.rcarz.jiraclient.BasicCredentials;
import net.rcarz.jiraclient.JiraClient;
import net.rcarz.jiraclient.JiraException;
import net.rcarz.jiraclient.RestException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.*;

import static javax.swing.JFileChooser.APPROVE_OPTION;
import static javax.swing.JOptionPane.*;

@SpringBootApplication
@PropertySources({
        @PropertySource("classpath:default-profile.xml"),
        @PropertySource(value = "file:${user.home}/" + J2aaApp.CONFIG_FILE_NAME, ignoreResourceNotFound = true)
})

public class J2aaApp {
    public static final String VERSION_KEY = "version";
    public static final String CONFIG_FILE_NAME = ".j2aa";
    public static final String DEFAULT_APP_TITLE = "Jira to ActionableAgile converter";
    public static final String DEFAULT_CONNECTION_PROFILE_FORMAT = "xml";

    public static final String KEY_BOARD_URL = "board_url";
    public static final String KEY_OUTPUT_FILE = "output_file_name";
    public static final String KEY_JQL_SUB_FILTER = "jql_sub_filter";

    public static final String KEY_USER_NAME = "default.username";
    public static final String KEY_PASSWORD = "default.password";

    @Getter
    private final JFrame appFrame;

    @Value("${" + KEY_BOARD_URL + ":}")
    @Getter
    @Setter
    private String boardUrl;

    @Value("${" + KEY_USER_NAME + ":}")
    @Getter
    @Setter
    private String userName;

    @Value("${" + KEY_PASSWORD + ":}")
    @Getter
    @Setter
    private String password;

    @Value("${" + KEY_OUTPUT_FILE + ":}")
    @Getter
    @Setter
    private String outputFileName;

    @Value("${" + KEY_JQL_SUB_FILTER + ":}")
    @Getter
    @Setter
    private String jqlSubFilter;

    @Getter
    @Setter
    private File connProfile;
    @Value("${user.dir}")
    private String lastConnFileDir;

    @Getter
    @Value("${" + VERSION_KEY + ":}")
    private final String version = null;
    private JPanel rootPanel;
    private JTextField fBoardURL;
    private JButton startButton;
    private JTextField fUsername;
    private JTextField fOutputFileName;
    private JTextField fJQLSubFilter;
    private JTextArea fLog;
    private JButton selectOutputFileButton;
    private JButton loadSettingsButton;
    private JButton saveSettingsButton;
    private JPasswordField fPassword;
    private JLabel labelBoardUrl;

    @Value("${profile:}")
    private String envProfile; // Profile, obtained from an environment

    public J2aaApp() {
        super();

        appFrame = new JFrame();
        appFrame.setContentPane(rootPanel);
        appFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        appFrame.setPreferredSize(new Dimension(800, 450));
        appFrame.pack();
        appFrame.getRootPane().setDefaultButton(startButton);

        startButton.addActionListener(actionEvent -> {
            Runnable r = this::doConversion;
            r.run();
        });

        saveSettingsButton.addActionListener(actionEvent -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setCurrentDirectory(new File(lastConnFileDir));
            chooser.setSelectedFile(getConnProfile());
            chooser.setDialogTitle("Укажите файл для сохранения настроек");
            chooser.setFileFilter(new FileNameExtensionFilter("Connection profiles (.xml)", "xml"));
            int returnVal = chooser.showSaveDialog(getAppFrame());
            if (returnVal == APPROVE_OPTION) {
                try {
                    File file = chooser.getSelectedFile();
                    if (FilenameUtils.getExtension(file.getAbsolutePath()).equals(""))
                        file = new File(file.getAbsoluteFile() + "." + DEFAULT_CONNECTION_PROFILE_FORMAT);

                    if (file.exists() && showConfirmDialog(getAppFrame(), String.format("Файл %s уже существует. Перезаписать?", file.getAbsoluteFile()), "Подтверждение", YES_NO_OPTION) != YES_OPTION)
                        return;

                    writeConnProfile(file);
                    lastConnFileDir = file.getParent();
                    showMessageDialog(getAppFrame(), String.format("Настройки сохранены в файл %s", file.getName()), "Сохранение настроек", INFORMATION_MESSAGE);
                } catch (IOException e) {
                    showMessageDialog(getAppFrame(), "Ошибка сохранения настроек", "Ошибка", ERROR_MESSAGE);
                }
            }
        });
        loadSettingsButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("Connection profiles (.xml)", "xml"));
            chooser.setDialogTitle("Выберите файл с настройками");
            chooser.setCurrentDirectory(new File(lastConnFileDir));
            int returnVal = chooser.showOpenDialog(getAppFrame());
            if (returnVal == APPROVE_OPTION) {
                try {
                    readConnProfile(chooser.getSelectedFile());
                    setData(this);
                    fLog.setText(null);
                    lastConnFileDir = chooser.getSelectedFile().getParent();
                } catch (IOException ex) {
                    showMessageDialog(getAppFrame(), String.format("не удалось прочитать файл %s", chooser.getSelectedFile().getName()), "Ошибка чтения файла", ERROR_MESSAGE);
                }
            }
        });
        selectOutputFileButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("выберите расположение и имя файла");
            chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
            chooser.setSelectedFile(new File(fOutputFileName.getText()));
            chooser.setCurrentDirectory(new File(fOutputFileName.getText()).getAbsoluteFile().getParentFile());
            if (chooser.showSaveDialog(getAppFrame()) == APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (FilenameUtils.getExtension(file.getName()).equals(""))
                    file = new File(file.getAbsoluteFile() + ".csv");
                fOutputFileName.setText(file.getAbsolutePath());
                getData(this);
            }
        });
        labelBoardUrl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                if (e.getClickCount() == 2) {
                    Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
                    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                        try {
                            String url = fBoardURL.getText();
                            if (showConfirmDialog(getAppFrame(),
                                    String.format("Перейти на доску '%s'?", url),
                                    "Открытие страницы", YES_NO_OPTION, INFORMATION_MESSAGE) == YES_OPTION) {
                                desktop.browse(URI.create(fBoardURL.getText()));
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        });
    }

    public static void main(String[] args) {
        File configFile = new File(System.getProperty("user.home") + "/" + CONFIG_FILE_NAME);
        if (!configFile.exists()) {
            try {
                IOUtils.copy(Objects.requireNonNull(Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream(CONFIG_FILE_NAME)), new FileOutputStream(configFile));
            } catch (IOException e) {
                Logger logger = LoggerFactory.getLogger(J2aaApp.class.getName());
                logger.error("Ошибка создания конфигурационный файла " + configFile.getAbsoluteFile());
            }
        }

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(J2aaApp.class).headless(false).run(args);

        EventQueue.invokeLater(() -> {
            J2aaApp j2aaApp = ctx.getBean(J2aaApp.class);
            j2aaApp.setData(j2aaApp);
            if (j2aaApp.envProfile != null && !j2aaApp.envProfile.isEmpty()) {
                try {
                    j2aaApp.readConnProfile(new File(j2aaApp.envProfile));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            j2aaApp.setAppTitle();
            j2aaApp.getAppFrame().setVisible(true);
        });
    }

    private void readConnProfile(File file) throws IOException {
        getData(this);
        Properties p = new Properties();
        FileInputStream fis = new FileInputStream(file.getAbsoluteFile());

        if (FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("xml"))
            p.loadFromXML(fis);
        else
            p.load(fis);

        setBoardUrl(p.getProperty(KEY_BOARD_URL));
        setJqlSubFilter(p.getProperty(KEY_JQL_SUB_FILTER));
        setOutputFileName(p.getProperty(KEY_OUTPUT_FILE));

        if (this.getBoardUrl() == null || this.getBoardUrl().trim().equals(""))
            throw new InvalidPropertiesFormatException(String.format("Не заполнены обязательные поля %s",
                    (this.getBoardUrl() == null || this.getBoardUrl().trim().equals("") ? " " + KEY_BOARD_URL : "")));
        setData(this);
        setConnProfile(file);
        setAppTitle();
    }

    private void writeConnProfile(File file) throws IOException {
        getData(this);

        Properties p = new Properties();
        FileOutputStream fos = new FileOutputStream(file.getAbsoluteFile());
        p.setProperty(KEY_BOARD_URL, this.getBoardUrl());
        p.setProperty(KEY_OUTPUT_FILE, this.getOutputFileName().trim());
        p.setProperty(KEY_JQL_SUB_FILTER, this.getJqlSubFilter());

        if (FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("xml"))
            p.storeToXML(fos, null);
        else
            p.store(fos, null);

        fos.flush();
        fos.close();
        setConnProfile(file);
        setAppTitle();
    }

    public void setData(J2aaApp data) {
        fBoardURL.setText(data.getBoardUrl());
        fUsername.setText(data.getUserName());
        fOutputFileName.setText(data.getOutputFileName());
        fJQLSubFilter.setText(data.getJqlSubFilter());
        fPassword.setText(data.getPassword());
    }

    public void getData(J2aaApp data) {
        data.setBoardUrl(fBoardURL.getText());
        data.setUserName(fUsername.getText());
        data.setOutputFileName(fOutputFileName.getText());
        data.setJqlSubFilter(fJQLSubFilter.getText());
        data.setPassword(String.valueOf(fPassword.getPassword()));
    }

    private void doConversion() {
        getData(this);

        List<String> missedparams = new ArrayList<>(10);
        if (getBoardUrl() == null || getBoardUrl().trim().isEmpty()) missedparams.add("Ссылка на доску");
        if (getUserName() == null || getUserName().trim().isEmpty()) missedparams.add("Пользователь");
        if (getPassword() == null || getPassword().trim().isEmpty()) missedparams.add("Пароль");
        if (getOutputFileName() == null || getOutputFileName().trim().isEmpty())
            missedparams.add("Файл для экспорта");
        if (missedparams.size() > 0) {
            showMessageDialog(getAppFrame(), "Не указаны обязательные параметры: " + String.join(", ", missedparams), "Ошибка", ERROR_MESSAGE);
            return;
        }

        // Парсим адрес доски. Вычисляя адрес jira и boardId (rapidView)
        String jiraUrl;
        String boardId = null;
        try {
            URL url = new URL(getBoardUrl());
            jiraUrl = url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort()) + "/";
            String query = url.getQuery();
            if (query != null && !query.trim().isEmpty()) {
                String[] tokens = query.split("&");
                for (String token : tokens) {
                    int i = token.indexOf("=");
                    if (i > 0 && token.substring(0, i).trim().equalsIgnoreCase("rapidView")) {
                        boardId = token.substring(i + 1).trim();
                        break;
                    }
                }
            }

            if (boardId == null || boardId.trim().isEmpty()
                    || url.getHost() == null || url.getHost().trim().isEmpty())
                throw new MalformedURLException("Указан неверный адрес доски");
        } catch (MalformedURLException e) {
            showMessageDialog(getAppFrame(), e.getMessage(), "Ошибка", ERROR_MESSAGE);
            return;
        }

        // Проверяем наличие выходного файла на диске
        File outputFile = new File(getOutputFileName());
        if (outputFile.exists() && showConfirmDialog(getAppFrame(), String.format("Файл %s существует. Перезаписать?", outputFile.getAbsoluteFile()), "Подтверждение", YES_NO_OPTION) != YES_OPTION) {
            showMessageDialog(getAppFrame(), "Конвертация остановлена", "Информация", INFORMATION_MESSAGE);
            return;
        }

        startButton.setEnabled(false);
        startButton.update(startButton.getGraphics());

        fLog.setText(null);

        fLog.append(String.format("Подключаемся к серверу: %s\n", jiraUrl));
        fLog.append(String.format("Пользователь %s\n", getUserName()));
        fLog.update(fLog.getGraphics());

        J2aaConverter converter = new J2aaConverter();

        // Подключаемся к доске и конвертируем данные
        try {
            startButton.setEnabled(false);
            startButton.update(startButton.getGraphics());

            JiraClient jiraClient = new JiraClient(jiraUrl, new BasicCredentials(getUserName(), getPassword()));
            Board board = Board.get(jiraClient.getRestClient(), Long.parseLong(boardId));
            fLog.append(String.format("Установлено соединение с доской: %s\n", board.getName()));
            fLog.update(fLog.getGraphics());

            Date startDate = new Date();

            converter.importFromJira(board, getJqlSubFilter(), (msg) -> {
                fLog.append(msg);
                fLog.update(fLog.getGraphics());
            });

            if (converter.getExportableIssues().size() > 0) {
                Date endDate = new Date();
                long timeInSec = (endDate.getTime() - startDate.getTime()) / 1000;
                fLog.append(String.format("Всего получено: %d issues. Время: %d сек. Скорость: %.2f issues/сек\n", converter.getExportableIssues().size(), timeInSec, (1.0 * converter.getExportableIssues().size()) / timeInSec));
                fLog.update(fLog.getGraphics());

                // экспортируем данные в файл
                converter.export2File(outputFile);

                fLog.append(String.format("\nДанные выгружены в файл:\n%s\n", outputFile.getAbsoluteFile()));
            } else
                fLog.append("Не найдены элементы для выгрузки, соответствующие заданным критериям.");
        } catch (JiraException e) {
            Exception ex = (Exception) e.getCause();
            if (ex instanceof SSLPeerUnverifiedException)
                fLog.append(String.format("SSL peer unverified: %s\n", ex.getMessage()));
            else if (ex instanceof UnknownHostException)
                fLog.append(String.format("Не удается соединиться с сервером %s\n", ex.getMessage()));
            else if (ex instanceof RestException) {
                if (((RestException) ex).getHttpStatusCode() == 401) {
                    fLog.append(ex.getMessage().substring(0, 56));
                } else {
                    fLog.append(ex.getMessage());
                }
            } else
                fLog.append(e.getMessage());

        } catch (IOException e) {
            fLog.append(e.getMessage());
        } finally {
            startButton.setEnabled(true);
            startButton.update(startButton.getGraphics());
        }
    }

    public void setAppTitle() {
        String newTitle = DEFAULT_APP_TITLE
                + (!version.isEmpty() ? " v" + version : "")
                + (connProfile != null ? " [" + connProfile.getName() + "]" : "");
        getAppFrame().setTitle(newTitle);
    }

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        rootPanel = new JPanel();
        rootPanel.setLayout(new GridLayoutManager(6, 3, new Insets(0, 0, 0, 10), -1, -1));
        labelBoardUrl = new JLabel();
        labelBoardUrl.setText("Ссылка на доску*");
        rootPanel.add(labelBoardUrl, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1, false));
        fBoardURL = new JTextField();
        rootPanel.add(fBoardURL, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JScrollPane scrollPane1 = new JScrollPane();
        rootPanel.add(scrollPane1, new GridConstraints(5, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        fLog = new JTextArea();
        fLog.setText("");
        scrollPane1.setViewportView(fLog);
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        rootPanel.add(panel1, new GridConstraints(5, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        panel1.add(spacer1, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        startButton = new JButton();
        startButton.setText("Конвертировать");
        panel1.add(startButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadSettingsButton = new JButton();
        loadSettingsButton.setText("Выбрать профиль");
        rootPanel.add(loadSettingsButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        saveSettingsButton = new JButton();
        saveSettingsButton.setText("Сохранить профиль");
        rootPanel.add(saveSettingsButton, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        selectOutputFileButton = new JButton();
        selectOutputFileButton.setText("Обзор");
        rootPanel.add(selectOutputFileButton, new GridConstraints(2, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        fPassword = new JPasswordField();
        rootPanel.add(fPassword, new GridConstraints(4, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Пароль*");
        rootPanel.add(label1, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1, false));
        fUsername = new JTextField();
        rootPanel.add(fUsername, new GridConstraints(3, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Пользователь*");
        rootPanel.add(label2, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1, false));
        final JLabel label3 = new JLabel();
        label3.setText("Файл для экспорта*");
        rootPanel.add(label3, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1, false));
        fOutputFileName = new JTextField();
        rootPanel.add(fOutputFileName, new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label4 = new JLabel();
        label4.setText("Доп.JQL фильтр");
        rootPanel.add(label4, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 1, false));
        fJQLSubFilter = new JTextField();
        fJQLSubFilter.setText("");
        rootPanel.add(fJQLSubFilter, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return rootPanel;
    }

}
