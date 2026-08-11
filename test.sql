delete from ndr_messages;
delete from ndr_message_log;
update hiv_art_clinical set tb_status = '67' where tb_status ilike 'TB%';