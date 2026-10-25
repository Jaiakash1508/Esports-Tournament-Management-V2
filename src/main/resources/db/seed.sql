INSERT INTO teams (team_id, tournament_id, name, college, game, roster_size) VALUES
    (1, 1, 'Beaver Circuit', 'Baker House', 'Valorant', 5),
    (2, 1, 'East Campus Ember', 'East Campus', 'Valorant', 5),
    (3, 1, 'Simmons Static', 'Simmons Hall', 'Valorant', 5);

INSERT INTO players (team_id, player_name, is_captain) VALUES
    (1, 'Priya Nair', 1), (1, 'Aarav Shah', 0), (1, 'Mina Lee', 0), (1, 'Noah Kim', 0), (1, 'Sara Das', 0),
    (2, 'Diego Ruiz', 1), (2, 'Ishaan Roy', 0), (2, 'Lena Park', 0), (2, 'Maya Chen', 0), (2, 'Ravi Kumar', 0),
    (3, 'Amara Chen', 1), (3, 'Zoya Ali', 0), (3, 'Ethan Lim', 0), (3, 'Nina Bose', 0), (3, 'Kabir Sen', 0);

INSERT INTO matches (match_id, tournament_id, team_a_id, team_b_id, score_a, score_b, reported) VALUES
    (1, 1, 1, 2, 13, 7, 1),
    (2, 1, 1, 3, 13, 10, 1),
    (3, 1, 2, 3, 9, 13, 1);

INSERT INTO match_history (description) VALUES
    ('Result reported: Beaver Circuit 13–7 East Campus Ember'),
    ('Result reported: Beaver Circuit 13–10 Simmons Static'),
    ('Result reported: East Campus Ember 9–13 Simmons Static');
