using System;
using System.Drawing;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>登录 / 注册窗口（两个选项卡）。</summary>
    public class LoginForm : Form
    {
        private readonly AppConfig _config;
        private readonly ApiClient _api;
        private readonly TabControl _tabs;
        private readonly Label _lblLoginStatus;
        private readonly Label _lblRegStatus;
        private bool _busy;

        public LoginForm(AppConfig config, ApiClient api)
        {
            _config = config;
            _api = api;

            Text = "LS Chat - 登录";
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ClientSize = new Size(360, 330);

            _tabs = new TabControl { Dock = DockStyle.Fill };

            // ---------- 登录 ----------
            var tabLogin = new TabPage("登录");
            var lblUser = new Label { Text = "用户 ID", AutoSize = true, Location = new Point(30, 25) };
            var txtUser = new TextBox { Location = new Point(30, 50), Width = 280 };
            var lblPass = new Label { Text = "密码", AutoSize = true, Location = new Point(30, 85) };
            var txtPass = new TextBox
            {
                Location = new Point(30, 110),
                Width = 280,
                UseSystemPasswordChar = true
            };
            _lblLoginStatus = new Label { Location = new Point(30, 148), Width = 280, Height = 18 };
            var btnLogin = new Button
            {
                Text = "登录",
                Location = new Point(30, 180),
                Width = 280,
                Height = 34
            };
            btnLogin.Click += async (s, e) => await DoLogin(txtUser.Text.Trim(), txtPass.Text);
            txtPass.KeyDown += async (s, e) =>
            {
                if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; await DoLogin(txtUser.Text.Trim(), txtPass.Text); }
            };
            txtUser.KeyDown += async (s, e) =>
            {
                if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; await DoLogin(txtUser.Text.Trim(), txtPass.Text); }
            };
            tabLogin.Controls.Add(lblUser);
            tabLogin.Controls.Add(txtUser);
            tabLogin.Controls.Add(lblPass);
            tabLogin.Controls.Add(txtPass);
            tabLogin.Controls.Add(_lblLoginStatus);
            tabLogin.Controls.Add(btnLogin);

            // ---------- 注册 ----------
            var tabRegister = new TabPage("注册");
            var lblRUser = new Label { Text = "用户名（3-32 位字母/数字/下划线）", AutoSize = true, Location = new Point(30, 20) };
            var txtRUser = new TextBox { Location = new Point(30, 44), Width = 280 };
            var lblRPass = new Label { Text = "密码（6-64 位）", AutoSize = true, Location = new Point(30, 79) };
            var txtRPass = new TextBox
            {
                Location = new Point(30, 103),
                Width = 280,
                UseSystemPasswordChar = true
            };
            var lblRConfirm = new Label { Text = "确认密码", AutoSize = true, Location = new Point(30, 138) };
            var txtRConfirm = new TextBox
            {
                Location = new Point(30, 162),
                Width = 280,
                UseSystemPasswordChar = true
            };
            _lblRegStatus = new Label { Location = new Point(30, 196), Width = 280, Height = 18 };
            var btnRegister = new Button
            {
                Text = "注册",
                Location = new Point(30, 224),
                Width = 280,
                Height = 34
            };
            btnRegister.Click += async (s, e) =>
                await DoRegister(txtRUser.Text.Trim(), txtRPass.Text, txtRConfirm.Text);
            tabRegister.Controls.Add(lblRUser);
            tabRegister.Controls.Add(txtRUser);
            tabRegister.Controls.Add(lblRPass);
            tabRegister.Controls.Add(txtRPass);
            tabRegister.Controls.Add(lblRConfirm);
            tabRegister.Controls.Add(txtRConfirm);
            tabRegister.Controls.Add(_lblRegStatus);
            tabRegister.Controls.Add(btnRegister);

            _tabs.TabPages.Add(tabLogin);
            _tabs.TabPages.Add(tabRegister);
            Controls.Add(_tabs);
        }

        private async System.Threading.Tasks.Task DoLogin(string userIdText, string password)
        {
            if (_busy) return;
            long userId;
            if (!long.TryParse(userIdText, out userId) || userId <= 0)
            {
                ShowStatus(_lblLoginStatus, "请输入有效的用户 ID", Color.Firebrick);
                return;
            }
            if (password.Length == 0)
            {
                ShowStatus(_lblLoginStatus, "请输入密码", Color.Firebrick);
                return;
            }
            _busy = true;
            try
            {
                await _api.Login(userId, password);
                DialogResult = DialogResult.OK;
                Close();
            }
            catch (Exception ex)
            {
                ShowStatus(_lblLoginStatus, ex.Message, Color.Firebrick);
            }
            finally
            {
                _busy = false;
            }
        }

        private async System.Threading.Tasks.Task DoRegister(string username, string password, string confirm)
        {
            if (_busy) return;
            if (username.Length == 0 || password.Length == 0)
            {
                ShowStatus(_lblRegStatus, "请输入完整信息", Color.Firebrick);
                return;
            }
            if (password != confirm)
            {
                ShowStatus(_lblRegStatus, "两次输入的密码不一致", Color.Firebrick);
                return;
            }
            _busy = true;
            try
            {
                User user = await _api.Register(username, password);
                ShowStatus(_lblRegStatus, "注册成功，你的用户 ID 是 " + user.id + "（登录时需使用 ID）", Color.DarkGreen);
                _tabs.SelectedIndex = 0;
            }
            catch (Exception ex)
            {
                ShowStatus(_lblRegStatus, ex.Message, Color.Firebrick);
            }
            finally
            {
                _busy = false;
            }
        }

        private static void ShowStatus(Label label, string text, Color color)
        {
            label.Text = text;
            label.ForeColor = color;
        }
    }
}
