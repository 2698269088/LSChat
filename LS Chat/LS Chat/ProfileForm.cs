using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>个人资料窗口：修改签名与状态。</summary>
    public class ProfileForm : Form
    {
        private readonly ApiClient _api;
        private readonly TextBox _txtSignature;
        private readonly TextBox _txtStatus;
        private readonly Label _lblStatus;

        public ProfileForm(ApiClient api)
        {
            _api = api;

            Text = "个人资料";
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ClientSize = new Size(360, 250);

            var lblSignature = new Label
            {
                Text = "个性签名（最多 30 字）",
                AutoSize = true,
                Location = new Point(20, 20)
            };
            _txtSignature = new TextBox
            {
                Location = new Point(20, 44),
                Width = 320,
                MaxLength = 30
            };
            var lblStatusTitle = new Label
            {
                Text = "个人状态（最多 20 字）",
                AutoSize = true,
                Location = new Point(20, 84)
            };
            _txtStatus = new TextBox
            {
                Location = new Point(20, 108),
                Width = 320,
                MaxLength = 20
            };
            _lblStatus = new Label { Location = new Point(20, 146), Width = 320, Height = 18 };
            var btnSave = new Button
            {
                Text = "保存",
                Location = new Point(20, 176),
                Width = 150,
                Height = 34
            };
            btnSave.Click += async (s, e) => await Save();
            var btnClose = new Button
            {
                Text = "关闭",
                Location = new Point(190, 176),
                Width = 150,
                Height = 34,
                DialogResult = DialogResult.Cancel
            };

            Controls.Add(lblSignature);
            Controls.Add(_txtSignature);
            Controls.Add(lblStatusTitle);
            Controls.Add(_txtStatus);
            Controls.Add(_lblStatus);
            Controls.Add(btnSave);
            Controls.Add(btnClose);
            AcceptButton = btnSave;
            CancelButton = btnClose;

            Load += async (s, e) => await LoadProfile();
        }

        private async System.Threading.Tasks.Task LoadProfile()
        {
            try
            {
                UserProfile profile = await _api.GetProfile();
                _txtSignature.Text = profile.signature;
                _txtStatus.Text = profile.status;
            }
            catch (Exception ex)
            {
                _lblStatus.Text = ex.Message;
                _lblStatus.ForeColor = Color.Firebrick;
            }
        }

        private async System.Threading.Tasks.Task Save()
        {
            try
            {
                await _api.SetProfile(_txtSignature.Text.Trim(), _txtStatus.Text.Trim());
                _lblStatus.Text = "已保存";
                _lblStatus.ForeColor = Color.DarkGreen;
            }
            catch (Exception ex)
            {
                _lblStatus.Text = ex.Message;
                _lblStatus.ForeColor = Color.Firebrick;
            }
        }
    }
}
