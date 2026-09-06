using System;
using System.Drawing;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>首次启动：配置服务器地址（测试连接并保存）。</summary>
    public class ConfigForm : Form
    {
        private readonly AppConfig _config;
        private readonly ApiClient _api;
        private readonly TextBox _txtHost;
        private readonly TextBox _txtPort;
        private readonly ComboBox _cmbProtocol;
        private readonly Label _lblStatus;
        private readonly Button _btnSave;
        private bool _busy;

        public ConfigForm(AppConfig config, ApiClient api)
        {
            _config = config;
            _api = api;

            Text = "LS Chat - 服务器配置";
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ClientSize = new Size(380, 330);

            var label = new Label
            {
                Text = "首次使用，请配置服务器地址",
                AutoSize = true,
                Location = new Point(20, 18),
                Font = new Font(Font.FontFamily, 10f, FontStyle.Bold)
            };

            var lblHost = new Label { Text = "服务器地址", AutoSize = true, Location = new Point(20, 60) };
            _txtHost = new TextBox
            {
                Location = new Point(20, 84),
                Width = 320,
                Text = config.ServerHost
            };

            var lblPort = new Label { Text = "端口", AutoSize = true, Location = new Point(20, 118) };
            _txtPort = new TextBox
            {
                Location = new Point(20, 142),
                Width = 120,
                Text = config.ServerPort.ToString()
            };

            var lblProtocol = new Label { Text = "通信协议", AutoSize = true, Location = new Point(20, 176) };
            _cmbProtocol = new ComboBox
            {
                Location = new Point(20, 200),
                Width = 320,
                DropDownStyle = ComboBoxStyle.DropDownList
            };
            _cmbProtocol.Items.Add("WebSocket（实时推送，默认）");
            _cmbProtocol.Items.Add("HTTP（轮询）");
            _cmbProtocol.SelectedIndex =
                string.Equals(config.Protocol, "http", StringComparison.OrdinalIgnoreCase) ? 1 : 0;

            _lblStatus = new Label
            {
                Location = new Point(20, 236),
                Width = 320,
                Height = 20,
                Text = ""
            };

            _btnSave = new Button
            {
                Text = "测试连接并保存",
                Location = new Point(20, 264),
                Width = 150,
                Height = 32
            };
            _btnSave.Click += BtnSave_Click;

            var btnClearPin = new Button
            {
                Text = "清除证书锁定",
                Location = new Point(190, 264),
                Width = 150,
                Height = 32,
                Enabled = !string.IsNullOrEmpty(config.PinnedCertSha256)
            };
            btnClearPin.Click += (s, e) =>
            {
                _config.PinnedCertSha256 = "";
                _config.Save();
                _lblStatus.ForeColor = Color.DarkGreen;
                _lblStatus.Text = "已清除信任的服务器证书，下次连接将重新信任";
                btnClearPin.Enabled = false;
            };

            Controls.Add(label);
            Controls.Add(lblHost);
            Controls.Add(_txtHost);
            Controls.Add(lblPort);
            Controls.Add(_txtPort);
            Controls.Add(lblProtocol);
            Controls.Add(_cmbProtocol);
            Controls.Add(_lblStatus);
            Controls.Add(_btnSave);
            Controls.Add(btnClearPin);
            AcceptButton = _btnSave;
        }

        private async void BtnSave_Click(object sender, EventArgs e)
        {
            if (_busy) return;
            string host = _txtHost.Text.Trim();
            int port;
            if (host.Length == 0 || !int.TryParse(_txtPort.Text.Trim(), out port) || port <= 0 || port > 65535)
            {
                ShowStatus("请输入有效的服务器地址和端口", Color.Firebrick);
                return;
            }
            _busy = true;
            _btnSave.Enabled = false;
            ShowStatus("正在连接...", Color.DimGray);
            try
            {
                _config.ServerHost = host;
                _config.ServerPort = port;
                _config.Protocol = _cmbProtocol.SelectedIndex == 1 ? "http" : "websocket";
                string info = await _api.Ping();
                _config.Save();
                DialogResult = DialogResult.OK;
                Close();
            }
            catch (Exception ex)
            {
                ShowStatus("无法连接服务器：" + ex.Message, Color.Firebrick);
            }
            finally
            {
                _busy = false;
                _btnSave.Enabled = true;
            }
        }

        private void ShowStatus(string text, Color color)
        {
            _lblStatus.Text = text;
            _lblStatus.ForeColor = color;
        }
    }
}
